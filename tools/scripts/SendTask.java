import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The PC half of the HOPA gateway, with no third-party dependencies.
 *
 * <pre>
 *   SendTask pair --base https://host --link hopa://pair?... --name DESKTOP --out tokens.json
 *   SendTask send --tokens tokens.json --goal "open settings" [--to device] [--timeout 200]
 * </pre>
 *
 * Pair trades a single-use pairing link for a windows device identity and
 * writes the tokens to the file the caller names. Send connects to the gateway,
 * says hello, sends one task.create, then prints the ack, the task.result or
 * task.error, and the traceparent that carried the task.
 *
 * The process never prints a token: only device ids, states, a summary and the
 * trace cross stdout. Every failure is a single line, never a stack trace.
 */
public final class SendTask {
    private static final String APP_VERSION = "0.1.0";
    private static final int HELLO_TIMEOUT_SEC = 20;
    private static final int ACK_TIMEOUT_SEC = 30;
    private static final int DEFAULT_RESULT_TIMEOUT_SEC = 200;

    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (IllegalArgumentException error) {
            System.err.println("send-task: " + error.getMessage());
            code = 1;
        } catch (Exception error) {
            System.err.println("send-task: " + describe(error));
            code = 1;
        }
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: SendTask pair|send --option value ...");
        }
        Map<String, String> options = options(Arrays.copyOfRange(args, 1, args.length));
        return switch (args[0]) {
            case "pair" -> pair(options);
            case "send" -> send(options);
            default -> throw new IllegalArgumentException("unknown mode '" + args[0] + "'; use pair or send");
        };
    }

    // ---------------------------------------------------------------- pair

    private static int pair(Map<String, String> options) throws Exception {
        String base = required(options, "base").replaceAll("/+$", "");
        Link link = Link.parse(required(options, "link"));
        String name = optional(options, "name", "HOPA-PC");
        Path tokensPath = Paths.get(required(options, "out"));

        String body = "{"
            + "\"session\":" + quote(link.session) + ","
            + "\"platform\":\"windows\","
            + "\"name\":" + quote(name) + ","
            + "\"publicKey\":" + quote(publicKey()) + ","
            + "\"alg\":\"x25519\"}";
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/pair/claim"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalArgumentException("backend unreachable: " + describe(error));
        }
        Map<String, Object> claimed = parseObject(response.body(), "the pairing response");
        if (response.statusCode() / 100 != 2) {
            throw new IllegalArgumentException("pairing refused: " + refusal(claimed, response.statusCode()));
        }

        String deviceId = text(claimed, "deviceId");
        String householdId = text(claimed, "householdId");
        String accessToken = text(claimed, "accessToken");
        String refreshToken = text(claimed, "refreshToken");
        String expiresAt = text(claimed, "expiresAt");
        if (deviceId.isBlank() || householdId.isBlank() || accessToken.isBlank()
            || refreshToken.isBlank() || expiresAt.isBlank()) {
            throw new IllegalArgumentException("the pairing response is missing the device identity or tokens");
        }

        String gateway = link.gateway.isBlank() ? websocketUrl(base) : link.gateway;
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("deviceId", deviceId);
        tokens.put("householdId", householdId);
        tokens.put("accessToken", accessToken);
        tokens.put("refreshToken", refreshToken);
        tokens.put("expiresAt", expiresAt);
        tokens.put("gatewayUrl", gateway);
        tokens.put("name", name);
        tokens.put("to", "");

        // The phone may already be online. Learning its id now keeps -To
        // optional later; failing to do so costs nothing, the tokens are valid.
        try {
            Gateway probe = Gateway.open(gateway, accessToken, name);
            try {
                if (probe.peers.size() == 1) {
                    tokens.put("to", probe.peers.get(0));
                }
            } finally {
                probe.close();
            }
        } catch (Exception ignored) {
            // send will retry the discovery when -To is absent
        }

        writeTokens(tokensPath, tokens);
        System.out.println("paired device=" + deviceId + " household=" + householdId + " expires=" + expiresAt);
        if (!tokens.get("to").isBlank()) {
            System.out.println("target=" + tokens.get("to"));
        }
        return 0;
    }

    private static String publicKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------------------------------------------------------------- send

    private static int send(Map<String, String> options) throws Exception {
        Path tokensPath = Paths.get(required(options, "tokens"));
        Map<String, Object> tokens = readObject(tokensPath, "the token file");
        String gateway = text(tokens, "gatewayUrl");
        String accessToken = text(tokens, "accessToken");
        if (gateway.isBlank() || accessToken.isBlank()) {
            throw new IllegalArgumentException("the token file has no gateway or access token; run send-task.ps1 -Pair first");
        }
        String name = optional(options, "name", text(tokens, "name"));
        String to = optional(options, "to", text(tokens, "to"));
        String goal = required(options, "goal");
        int resultTimeout = intOption(options, "timeout", DEFAULT_RESULT_TIMEOUT_SEC);

        Gateway link = Gateway.open(gateway, accessToken, name);
        try {
            if (to.isBlank()) {
                if (link.peers.size() == 1) {
                    to = link.peers.get(0);
                    System.out.println("target=" + to + " (the only other device)");
                } else if (link.peers.isEmpty()) {
                    throw new IllegalArgumentException(
                        "no other device in this household; start the phone or pass -To <deviceId>");
                } else {
                    throw new IllegalArgumentException(
                        "several devices in this household; pass -To <deviceId>");
                }
            }

            String traceparent = newTraceparent();
            // A fresh id is the idempotency key: a resend of this exact run is
            // answered with the stored ack instead of a second delivery.
            String body = "{"
                + "\"v\":1,"
                + "\"id\":" + quote(UUID.randomUUID().toString()) + ","
                + "\"type\":\"task.create\","
                + "\"ts\":" + System.currentTimeMillis() + ","
                + "\"trace\":" + quote(traceparent) + ","
                + "\"to\":" + quote(to) + ","
                + "\"body\":{\"goal\":" + quote(goal) + "}}";
            link.sendText(body);

            Map<String, Object> ack = link.awaitType("task.ack", ACK_TIMEOUT_SEC);
            Map<String, Object> ackBody = object(ack.get("body"));
            String task = firstNonBlank(text(ackBody, "task"), text(ack, "task"));
            if (task.isBlank()) {
                throw new IllegalArgumentException("the ack carried no task id");
            }
            // The ack's trace is the one to look up when the terminal frame
            // never arrives (a woken phone that cannot run, a lost socket).
            System.out.println(
                "ack task=" + task
                    + " trace=" + text(ack, "trace")
                    + " state=" + text(ackBody, "state"));

            Map<String, Object> terminal = link.awaitTask(task, resultTimeout);
            Map<String, Object> terminalBody = object(terminal.get("body"));
            String type = text(terminal, "type");
            String trace = firstNonBlank(text(terminal, "trace"), text(ack, "trace"), traceparent);
            int code = 0;
            if ("task.result".equals(type)) {
                System.out.println("result summary=" + text(terminalBody, "summary"));
            } else {
                System.out.println(
                    "error code=" + text(terminalBody, "code")
                        + " message=" + text(terminalBody, "message"));
                code = 1;
            }
            System.out.println("trace=" + trace);
            return code;
        } finally {
            link.close();
        }
    }

    private static String newTraceparent() {
        SecureRandom random = new SecureRandom();
        byte[] trace = new byte[16];
        byte[] span = new byte[8];
        random.nextBytes(trace);
        random.nextBytes(span);
        return "00-" + hex(trace) + "-" + hex(span) + "-01";
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }

    // ------------------------------------------------------------- gateway

    /** One socket plus the frames it has delivered, in arrival order. */
    private static final class Gateway implements AutoCloseable {
        private final WebSocket socket;
        private final Frames frames;
        private final String name;
        private final List<String> peers = new ArrayList<>();
        private String self = "";

        private Gateway(WebSocket socket, Frames frames, String name) {
            this.socket = socket;
            this.frames = frames;
            this.name = name;
        }

        static Gateway open(String url, String token, String name) throws Exception {
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                throw new IllegalArgumentException("the gateway URL is not ws:// or wss://");
            }
            Frames frames = new Frames();
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
            WebSocket socket;
            try {
                socket = http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(URI.create(url), frames)
                    .get(30, TimeUnit.SECONDS);
            } catch (ExecutionException error) {
                throw new IllegalArgumentException("could not open the gateway: " + describe(error.getCause()));
            } catch (Exception error) {
                throw new IllegalArgumentException("could not open the gateway: " + describe(error));
            }
            Gateway gateway = new Gateway(socket, frames, name);
            try {
                gateway.hello();
            } catch (Exception error) {
                gateway.close();
                throw error;
            }
            return gateway;
        }

        private void hello() throws Exception {
            String body = "{"
                + "\"v\":1,"
                + "\"id\":" + quote(UUID.randomUUID().toString()) + ","
                + "\"type\":\"device.hello\","
                + "\"ts\":" + System.currentTimeMillis() + ","
                + "\"body\":{\"platform\":\"windows\",\"name\":" + quote(name)
                + ",\"appVersion\":" + quote(APP_VERSION) + "}}";
            sendText(body);
            Map<String, Object> ack = awaitType("device.hello.ack", HELLO_TIMEOUT_SEC);
            self = text(ack, "to");
            for (Object peer : array(object(ack.get("body")).get("peers"))) {
                String id = String.valueOf(peer);
                if (!id.isBlank() && !id.equals(self)) {
                    peers.add(id);
                }
            }
        }

        void sendText(String text) throws Exception {
            try {
                socket.sendText(text, true).get(15, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new IllegalArgumentException("could not send on the gateway: " + describe(error));
            }
        }

        Map<String, Object> awaitType(String type, int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                Map<String, Object> frame = next(deadline, type);
                String actual = text(frame, "type");
                if ("error".equals(actual)) {
                    throw protocolError(frame);
                }
                if (type.equals(actual)) {
                    return frame;
                }
            }
        }

        Map<String, Object> awaitTask(String task, int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (true) {
                Map<String, Object> frame = next(deadline, "the result of " + task);
                String type = text(frame, "type");
                if ("error".equals(type)) {
                    throw protocolError(frame);
                }
                if (("task.result".equals(type) || "task.error".equals(type))
                    && task.equals(text(frame, "task"))) {
                    return frame;
                }
            }
        }

        private Map<String, Object> next(long deadline, String what) throws Exception {
            Map<String, Object> frame = frames.poll(deadline);
            if (frame == null) {
                throw new IllegalArgumentException("timed out waiting for " + what);
            }
            return frame;
        }

        @Override
        public void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            } catch (Exception ignored) {
                // an already closed socket needs nothing
            }
        }
    }

    private static IllegalArgumentException protocolError(Map<String, Object> frame) {
        Map<String, Object> body = object(frame.get("body"));
        return new IllegalArgumentException(
            "gateway error " + text(body, "code") + ": " + text(body, "message"));
    }

    /** Collects complete text frames and reports why the socket ended. */
    private static final class Frames implements WebSocket.Listener {
        private final BlockingQueue<Map<String, Object>> ready = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile boolean closed;
        private volatile String closeReason = "";
        private volatile Throwable failure;

        Map<String, Object> poll(long deadline) throws Exception {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                Map<String, Object> frame = ready.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS);
                if (frame != null) {
                    return frame;
                }
                if (failure != null) {
                    throw new IllegalArgumentException("the gateway failed: " + describe(failure));
                }
                if (closed) {
                    Map<String, Object> last = ready.poll();
                    if (last != null) {
                        return last;
                    }
                    throw new IllegalArgumentException(
                        "the gateway closed the socket" + (closeReason.isBlank() ? "" : ": " + closeReason));
                }
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    Object parsed = Json.parse(text);
                    if (parsed instanceof Map) {
                        ready.add(object(parsed));
                    }
                } catch (RuntimeException ignored) {
                    // one malformed frame must not kill the socket
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed = true;
            closeReason = statusCode + (reason.isBlank() ? "" : " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            failure = error;
        }
    }

    // ---------------------------------------------------------------- link

    /** The two values the PC needs from a hopa://pair link. */
    private static final class Link {
        private final String session;
        private final String gateway;

        private Link(String session, String gateway) {
            this.session = session;
            this.gateway = gateway;
        }

        static Link parse(String text) {
            URI uri;
            try {
                uri = URI.create(text.trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("not a hopa://pair link");
            }
            if (!"hopa".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("not a hopa://pair link");
            }
            Map<String, String> query = query(uri.getRawQuery());
            String session = query.getOrDefault("p", "").trim();
            if (session.isEmpty()) {
                throw new IllegalArgumentException("the pairing link has no session");
            }
            String gateway = query.getOrDefault("u", "").trim();
            if (!gateway.isEmpty() && !gateway.startsWith("ws://") && !gateway.startsWith("wss://")) {
                throw new IllegalArgumentException("the pairing link has a bad gateway URL");
            }
            return new Link(session, gateway);
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> values = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                return values;
            }
            for (String entry : raw.split("&")) {
                int separator = entry.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                values.put(
                    decode(entry.substring(0, separator)),
                    decode(entry.substring(separator + 1)));
            }
            return values;
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException error) {
                return value;
            }
        }
    }

    private static String websocketUrl(String base) {
        String url = base.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
        if (url.endsWith("/v1/gateway")) {
            return url;
        }
        return url.replaceAll("/+$", "") + "/v1/gateway";
    }

    // --------------------------------------------------------------- options

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + arg + "'");
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException(arg + " needs a value");
            }
            values.put(arg.substring(2), args[++index]);
        }
        return values;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value.trim();
    }

    private static String optional(Map<String, String> options, String name, String fallback) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value.trim();
    }

    private static int intOption(Map<String, String> options, String name, int fallback) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("--" + name + " must be a positive whole number of seconds");
        }
    }

    // ----------------------------------------------------------------- files

    private static Map<String, Object> readObject(Path path, String what) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalArgumentException("cannot read " + what + ": " + path);
        }
        return parseObject(text, what);
    }

    private static Map<String, Object> parseObject(String text, String what) {
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(what + " is not JSON");
        }
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException(what + " is not a JSON object");
        }
        return object(parsed);
    }

    private static void writeTokens(Path target, Map<String, String> tokens) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
            first = false;
        }
        json.append("}\n");

        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(
            temp,
            json.toString(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------ json bits

    private static String refusal(Map<String, Object> error, int status) {
        String code = text(error, "code");
        String message = text(error, "message");
        String detail = (code + " " + message).trim();
        return detail.isEmpty() ? "HTTP " + status : "HTTP " + status + " " + detail;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Just enough JSON for flat gateway frames: objects, arrays, strings. */
    private static final class Json {
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            Object value = json.value();
            json.space();
            if (json.at != text.length()) {
                throw new IllegalArgumentException("trailing JSON data");
            }
            return value;
        }

        private Object value() {
            space();
            if (at >= text.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            at++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (peek('}')) {
                at++;
                return map;
            }
            while (true) {
                space();
                String key = string();
                space();
                expect(':');
                map.put(key, value());
                space();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            at++;
            List<Object> list = new ArrayList<>();
            space();
            if (peek(']')) {
                at++;
                return list;
            }
            while (true) {
                list.add(value());
                space();
                if (peek(',')) {
                    at++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    break;
                }
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw new IllegalArgumentException("bad JSON unicode escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("bad JSON escape");
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, at)) {
                throw new IllegalArgumentException("bad JSON literal");
            }
            at += expected.length();
            return value;
        }

        private Object number() {
            int start = at;
            while (at < text.length()) {
                char c = text.charAt(at);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    at++;
                } else {
                    break;
                }
            }
            if (start == at) {
                throw new IllegalArgumentException("bad JSON value");
            }
            try {
                return Double.valueOf(text.substring(start, at));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("bad JSON number");
            }
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean peek(char expected) {
            return at < text.length() && text.charAt(at) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw new IllegalArgumentException("expected '" + expected + "' in JSON");
            }
            at++;
        }
    }
}
