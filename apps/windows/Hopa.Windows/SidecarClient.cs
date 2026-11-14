using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Hopa.Windows;

/// <summary>The .NET half of the NDJSON protocol with the Kotlin engine.</summary>
public sealed class SidecarClient : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = null,
    };

    private readonly Process process;
    private readonly StreamWriter input;
    private readonly StreamReader output;
    private readonly Dictionary<string, TaskCompletionSource<JsonObject>> pending = new();
    private readonly Task readLoop;

    public event Action<string>? LogReceived;

    public event Action<JsonObject>? DeviceRequested;

    public event Action<JsonObject>? ApprovalRequested;

    public event Action<JsonObject>? SessionUpdated;

    public SidecarClient()
    {
        var lib = Environment.GetEnvironmentVariable("HOPA_SIDECAR_LIB");
        if (string.IsNullOrEmpty(lib))
        {
            var installed = Path.Combine(AppContext.BaseDirectory, "sidecar", "lib");
            lib = Directory.Exists(installed)
                ? installed
                : Path.GetFullPath(Path.Combine(
                    AppContext.BaseDirectory,
                    "..", "..", "..", "..",
                    "sidecar", "build", "install", "hopa-windows-sidecar", "lib"));
        }

        if (!Directory.Exists(lib))
        {
            throw new InvalidOperationException(
                $"sidecar library not found at {lib}; run gradlew -p apps/windows/sidecar installDist");
        }

        var nextToApp = Path.Combine(AppContext.BaseDirectory, "runtime", "bin", "java.exe");
        var sibling = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "runtime", "bin", "java.exe"));
        var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        var java = File.Exists(nextToApp)
            ? nextToApp
            : File.Exists(sibling)
                ? sibling
                : string.IsNullOrWhiteSpace(javaHome)
                    ? "java"
                    : Path.Combine(javaHome, "bin", "java.exe");
        var start = new ProcessStartInfo(java)
        {
            Arguments = $"-cp \"{lib}\\*\" com.hopa.sidecar.MainKt",
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8,
        };
        process = Process.Start(start)
            ?? throw new InvalidOperationException("java did not start");
        input = process.StandardInput;
        output = process.StandardOutput;
        readLoop = Task.Run(ReadLoopAsync);
        _ = Task.Run(() =>
        {
            string? line;
            while ((line = process.StandardError.ReadLine()) != null)
            {
                LogReceived?.Invoke(line);
            }
        });
    }

    public async Task<JsonObject> RequestAsync(string type, JsonObject fields, TimeSpan timeout)
    {
        var id = $"r-{Guid.NewGuid():N}";
        var completion = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (pending)
        {
            pending[id] = completion;
        }

        var message = new JsonObject
        {
            ["type"] = type,
            ["id"] = id,
        };
        foreach (var pair in fields)
        {
            message[pair.Key] = pair.Value?.DeepClone();
        }

        Send(message);
        var finished = await Task.WhenAny(completion.Task, Task.Delay(timeout));
        if (finished != completion.Task)
        {
            lock (pending)
            {
                pending.Remove(id);
            }

            throw new TimeoutException($"the sidecar did not answer {type} in {timeout.TotalSeconds:0} s");
        }

        return await completion.Task;
    }

    public async Task<(string Answer, string ConversationId, string Title, JsonArray? Steps, bool ChainOk)> StartTurnAsync(
        string goal,
        string? conversationId)
    {
        var fields = new JsonObject { ["goal"] = goal };
        if (conversationId != null)
        {
            fields["conversationId"] = conversationId;
        }

        var response = await RequestAsync("turn.start", fields, TimeSpan.FromSeconds(120));
        return (
            response["answer"]?.GetValue<string>() ?? "",
            response["conversationId"]?.GetValue<string>() ?? "",
            response["title"]?.GetValue<string>() ?? "New chat",
            response["steps"] as JsonArray,
            response["chainOk"]?.GetValue<bool>() == true);
    }

    public async Task<List<JsonObject>> ListConversationsAsync(bool includeArchived = false)
    {
        var response = await RequestAsync(
            "conversations.list",
            new JsonObject { ["includeArchived"] = includeArchived },
            TimeSpan.FromSeconds(10));
        var items = new List<JsonObject>();
        if (response["items"] is JsonArray array)
        {
            foreach (var item in array)
            {
                if (item is JsonObject obj)
                {
                    items.Add(obj);
                }
            }
        }

        return items;
    }

    public async Task<JsonObject?> CreateConversationAsync()
    {
        var response = await RequestAsync("conversation.create", new JsonObject(), TimeSpan.FromSeconds(10));
        return response["conversation"] as JsonObject;
    }

    public async Task<JsonObject?> RenameConversationAsync(string id, string title)
    {
        var response = await RequestAsync(
            "conversation.rename",
            new JsonObject { ["conversationId"] = id, ["title"] = title },
            TimeSpan.FromSeconds(10));
        return response["conversation"] as JsonObject;
    }

    public async Task<JsonObject?> PinConversationAsync(string id, bool pinned)
    {
        var response = await RequestAsync(
            "conversation.pin",
            new JsonObject { ["conversationId"] = id, ["pinned"] = pinned },
            TimeSpan.FromSeconds(10));
        return response["conversation"] as JsonObject;
    }

    public async Task<JsonObject?> ArchiveConversationAsync(string id, bool archived)
    {
        var response = await RequestAsync(
            "conversation.archive",
            new JsonObject { ["conversationId"] = id, ["archived"] = archived },
            TimeSpan.FromSeconds(10));
        return response["conversation"] as JsonObject;
    }

    public async Task<bool> DeleteConversationAsync(string id)    {
        var response = await RequestAsync(
            "conversation.delete",
            new JsonObject { ["conversationId"] = id },
            TimeSpan.FromSeconds(10));
        return response["ok"]?.GetValue<bool>() == true;
    }

    public async Task<string> ExportConversationAsync(string id)
    {
        var response = await RequestAsync(
            "conversation.export",
            new JsonObject { ["conversationId"] = id },
            TimeSpan.FromSeconds(10));
        return response["json"]?.GetValue<string>() ?? "";
    }

    public async Task<List<JsonObject>> SearchConversationsAsync(string query)
    {
        var response = await RequestAsync(
            "conversation.search",
            new JsonObject { ["query"] = query },
            TimeSpan.FromSeconds(10));
        var items = new List<JsonObject>();
        if (response["items"] is JsonArray array)
        {
            foreach (var item in array)
            {
                if (item is JsonObject obj)
                {
                    items.Add(obj);
                }
            }
        }

        return items;
    }

    public async Task<JsonObject?> OpenConversationAsync(string id)
    {
        var response = await RequestAsync(
            "conversation.open",
            new JsonObject { ["conversationId"] = id },
            TimeSpan.FromSeconds(10));
        return response["conversation"] as JsonObject;
    }

    public async Task<JsonObject> ConfigureAsync(AppSettings settings, string bridgeToken, string modelKey)
    {
        var fields = new JsonObject
        {
            ["bridgeUrl"] = settings.BridgeUrl,
            ["useBridge"] = settings.UseBridge,
            ["bridgeToken"] = bridgeToken,
            ["bridgeRefresh"] = CredentialStore.Read("hopa.bridge.refresh"),
            ["modelProvider"] = settings.ModelProvider,
            ["modelBaseUrl"] = settings.ModelBaseUrl,
            ["modelName"] = settings.ModelName,
            ["modelKey"] = modelKey,
            ["tone"] = settings.Tone,
        };
        return await RequestAsync("configure", fields, TimeSpan.FromSeconds(10));
    }

    public void SendApprovalResult(string id, bool allow)
    {
        Send(new JsonObject
        {
            ["type"] = "approval.result",
            ["id"] = id,
            ["allow"] = allow,
        });
    }

    public void SendDeviceResult(
        string id,
        bool ok,
        string? error = null,
        JsonObject? observation = null,
        string? app = null)
    {
        var message = new JsonObject
        {
            ["type"] = "device.result",
            ["id"] = id,
            ["ok"] = ok,
        };
        if (error != null)
        {
            message["error"] = error;
        }

        if (observation != null)
        {
            message["observation"] = observation;
        }

        if (app != null)
        {
            message["app"] = app;
        }

        Send(message);
    }

    private void Send(JsonObject message)
    {
        lock (input)
        {
            input.WriteLine(message.ToJsonString(JsonOptions));
            input.Flush();
        }
    }

    private async Task ReadLoopAsync()
    {
        string? line;
        while ((line = await output.ReadLineAsync()) != null)
        {
            JsonObject? message;
            try
            {
                message = JsonNode.Parse(line) as JsonObject;
            }
            catch (JsonException)
            {
                continue;
            }

            if (message == null)
            {
                continue;
            }

            switch (message["type"]?.GetValue<string>())
            {
                case "ready":
                    break;
                case "log":
                    LogReceived?.Invoke(message["text"]?.GetValue<string>() ?? "");
                    break;
                case "device.request":
                    DeviceRequested?.Invoke(message);
                    break;
                case "approval.request":
                    ApprovalRequested?.Invoke(message);
                    break;
                case "session":
                    SessionUpdated?.Invoke(message);
                    break;
                default:
                    var id = message["id"]?.GetValue<string>();
                    if (id != null)
                    {
                        TaskCompletionSource<JsonObject>? completion;
                        lock (pending)
                        {
                            pending.Remove(id, out completion);
                        }

                        completion?.TrySetResult(message);
                    }

                    break;
            }
        }
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            Send(new JsonObject { ["type"] = "shutdown" });
            if (!process.WaitForExit(3000))
            {
                process.Kill(true);
            }
        }
        catch (InvalidOperationException)
        {
            // The process already exited.
        }

        await readLoop.WaitAsync(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
        process.Dispose();
    }
}
