using System.Collections.ObjectModel;
using System.IO;
using System.Net.Http;
using System.Speech.Synthesis;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using System.Windows.Shell;

namespace Hopa.Windows;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<ConversationVm> conversations = new();
    private readonly List<ConversationVm> allConversations = new();
    private readonly ObservableCollection<MessageVm> messages = new();
    private readonly Dictionary<string, string> drafts = new();
    private readonly List<string> log = new();
    private readonly WindowsDevice device = new();
    private SpeechSynthesizer? speech;
    private System.Windows.Forms.NotifyIcon? tray;
    private string lastAnswer = "";
    private SidecarClient? client;
    private Task? connecting;
    private string? currentId;
    private JsonArray? lastSteps;
    private bool lastChainOk;
    private string? autoApproval;
    private string? outputPath;
    private bool switching;
    private bool running;

    public MainWindow()
    {
        InitializeComponent();
        // The Harmony laptop app hides the system decoration and keeps its own
        // window controls. WindowChrome does the same here: the top 52 px drags,
        // the three buttons stay clickable, the resize border remains.
        WindowChrome.SetWindowChrome(this, new WindowChrome
        {
            CaptionHeight = 52,
            ResizeBorderThickness = new Thickness(6),
            GlassFrameThickness = new Thickness(0),
            CornerRadius = new CornerRadius(0),
            UseAeroCaptionButtons = false,
        });
        foreach (var button in new[] { MinimizeButton, MaximizeButton, CloseButton })
        {
            WindowChrome.SetIsHitTestVisibleInChrome(button, true);
        }

        StateChanged += (_, _) => MaximizeButton.Content = WindowState == WindowState.Maximized
            ? "\u2750"
            : "\u25A1";
        ConversationList.ItemsSource = conversations;
        MessagesList.ItemsSource = messages;
        SetupTray();
        Loaded += (_, _) => RestoreWindowState();
        Closing += (_, _) => SaveWindowState();
        Closed += (_, _) =>
        {
            tray?.Dispose();
            speech?.Dispose();
            device.Dispose();
        };
        Loaded += async (_, _) => await ConnectAsync();
    }

    private void SetupTray()
    {
        tray = new System.Windows.Forms.NotifyIcon
        {
            Icon = System.Drawing.SystemIcons.Information,
            Text = "HOPA",
            Visible = true,
        };
        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowFromTray());
        menu.Items.Add("Exit", null, (_, _) => Close());
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => ShowFromTray();
    }

    private void ShowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized)
        {
            WindowState = WindowState.Normal;
        }

        Activate();
    }

    private void RestoreWindowState()
    {
        var settings = new SettingsStore().Load();
        if (settings.WindowWidth >= 800 && settings.WindowHeight >= 500)
        {
            Width = settings.WindowWidth;
            Height = settings.WindowHeight;
        }

        if (!double.IsNaN(settings.WindowLeft) && !double.IsNaN(settings.WindowTop))
        {
            Left = settings.WindowLeft;
            Top = settings.WindowTop;
        }

        if (settings.WindowMaximized)
        {
            WindowState = WindowState.Maximized;
        }
    }

    private void SaveWindowState()
    {
        var settings = new SettingsStore();
        var current = settings.Load();
        var bounds = WindowState == WindowState.Normal
            ? new Rect(Left, Top, Width, Height)
            : RestoreBounds;
        current.WindowLeft = bounds.Left;
        current.WindowTop = bounds.Top;
        current.WindowWidth = bounds.Width;
        current.WindowHeight = bounds.Height;
        current.WindowMaximized = WindowState == WindowState.Maximized;
        settings.Save(current);
    }

    private void Minimize_Click(object sender, RoutedEventArgs e)
    {
        SystemCommands.MinimizeWindow(this);
    }

    private void Maximize_Click(object sender, RoutedEventArgs e)
    {
        if (WindowState == WindowState.Maximized)
        {
            SystemCommands.RestoreWindow(this);
        }
        else
        {
            SystemCommands.MaximizeWindow(this);
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e)
    {
        SystemCommands.CloseWindow(this);
    }

    public async Task RunTurnAsync(string goal, string? outPath, string? shotPath, string? approval = null)
    {
        outputPath = outPath;
        autoApproval = approval;
        await ConnectAsync();
        await SendAsync(goal);
        WriteLog();
        if (shotPath != null)
        {
            await Task.Delay(400);
            SaveShot(shotPath);
        }

        Application.Current.Shutdown();
    }

    public async Task ListAsync(string? outPath)
    {
        outputPath = outPath;
        await ConnectAsync();
        foreach (var conversation in conversations)
        {
            Log($"{conversation.Id} | {conversation.Title} | {conversation.MessageCount}");
        }

        WriteLog();
        Application.Current.Shutdown();
    }

    public void SaveShot(string path) => WindowShot.Save(this, path);

    public async Task ShotSettingsAsync(string path)
    {
        await ConnectAsync();
        var dialog = new SettingsWindow { Owner = this };
        dialog.Show();
        await Task.Delay(600);
        WindowShot.Save(dialog, path);
        dialog.Close();
        Application.Current.Shutdown();
    }

    public async Task DraftCheckAsync(string path)
    {
        await ConnectAsync();
        var lines = new List<string>();
        if (client == null || currentId == null)
        {
            lines.Add("draft round-trip: FAIL (not connected)");
            File.WriteAllLines(path, lines);
            Application.Current.Shutdown();
            return;
        }

        var firstId = currentId;
        var other = conversations.FirstOrDefault(item => item.Id != firstId);
        if (other == null)
        {
            var created = await client.CreateConversationAsync();
            var id = created?["id"]?.GetValue<string>();
            if (id != null)
            {
                conversations.Add(ToVm(created!));
                other = conversations.First(item => item.Id == id);
            }
        }

        if (other == null)
        {
            lines.Add("draft round-trip: FAIL (no second conversation)");
            File.WriteAllLines(path, lines);
            Application.Current.Shutdown();
            return;
        }

        Composer.Text = "draft one";
        await SelectAsync(other.Id);
        var cleared = Composer.Text;
        await SelectAsync(firstId);
        var restored = Composer.Text;
        lines.Add($"second conversation: {other.Id}");
        lines.Add($"composer after switch: '{cleared}' (expected '')");
        lines.Add($"draft restored: '{restored}' (expected 'draft one')");
        lines.Add($"draft round-trip: {(cleared.Length == 0 && restored == "draft one" ? "ok" : "FAIL")}");
        File.WriteAllLines(path, lines);
        Application.Current.Shutdown();
    }

    public async Task BridgeCheckAsync(string url, string token, string path)
    {
        var settings = new SettingsStore();
        var current = settings.Load();
        current.BridgeUrl = url;
        current.UseBridge = true;
        settings.Save(current);
        CredentialStore.Write("hopa.bridge.token", token);

        outputPath = path;
        autoApproval = "allow";
        await ConnectAsync();
        await SendAsync("open settings on the phone");
        WriteLog();
        Application.Current.Shutdown();
    }

    public async Task HistoryCheckAsync(string path)
    {
        outputPath = path;
        await ConnectAsync();
        var lines = new List<string>();
        if (client == null)
        {
            lines.Add("history check: FAIL (not connected)");
            File.WriteAllLines(path, lines);
            Application.Current.Shutdown();
            return;
        }

        var created = await client.CreateConversationAsync();
        var id = created?["id"]?.GetValue<string>() ?? "";
        string renamed = "FAIL";
        var hits = new List<JsonObject>();
        var exported = "";
        var deleted = false;
        var remaining = -1;
        try
        {
            renamed = await client.RenameConversationAsync(id, "Groceries") is { } r
                ? r["title"]?.GetValue<string>() ?? "FAIL"
                : "FAIL";
            hits = await client.SearchConversationsAsync("Grocer");
            exported = await client.ExportConversationAsync(id);
            deleted = await client.DeleteConversationAsync(id);
            remaining = (await client.ListConversationsAsync()).Count;
        }
        catch (Exception error)
        {
            lines.Add($"error: {error.Message}");
        }

        lines.Add($"created: {(id.Length > 0 ? "ok" : "FAIL")}");
        lines.Add($"renamed: {renamed}");
        lines.Add($"search hits: {hits.Count}");
        lines.Add($"export has title: {exported.Contains("\"Groceries\"")}");
        lines.Add($"delete: {deleted}");
        lines.Add($"conversations left: {remaining}");
        File.WriteAllLines(path, lines);
        Application.Current.Shutdown();
    }

    public async Task PcCheckAsync(string path)
    {
        var settings = new SettingsStore();
        var current = settings.Load();
        current.UseBridge = false;
        settings.Save(current);

        outputPath = path;
        autoApproval = "allow";
        await ConnectAsync();
        await SendAsync("open notepad");
        await SendAsync("type hello in notepad");
        WriteLog();
        Application.Current.Shutdown();
    }

    public async Task PairAsync(string url, string code, string path)
    {
        var (ok, token, refresh) = await PostAsync(url, "/pair", new { code });
        if (ok && token.Length > 0)
        {
            CredentialStore.Write("hopa.bridge.token", token);
            CredentialStore.Write("hopa.bridge.refresh", refresh);
            var settings = new SettingsStore();
            var current = settings.Load();
            current.BridgeUrl = url;
            current.UseBridge = true;
            settings.Save(current);
        }

        File.WriteAllLines(path, new[]
        {
            $"pair: {(ok && token.Length > 0 ? "ok" : "FAIL")}",
            $"refresh stored: {refresh.Length > 0}",
        });
        Application.Current.Shutdown();
    }

    public async Task StaleCheckAsync(string url, string path)
    {
        var staleToken = CredentialStore.Read("hopa.bridge.token");
        var refresh = CredentialStore.Read("hopa.bridge.refresh");
        var (ok, newToken, newRefresh) = await PostAsync(url, "/refresh", new { refresh });
        // Keep the old token (now rejected) and hand the sidecar the rotated refresh.
        CredentialStore.Write("hopa.bridge.refresh", newRefresh);
        outputPath = path;
        autoApproval = "allow";
        await ConnectAsync();
        await SendAsync("open settings on the phone");
        Log($"stale check: rotation={(ok && newToken.Length > 0 ? "ok" : "FAIL")} " +
            $"token_changed={newToken != staleToken}");
        WriteLog();
        Application.Current.Shutdown();
    }

    private static async Task<(bool Ok, string Token, string Refresh)> PostAsync(
        string url,
        string path,
        object body)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        var payload = JsonSerializer.Serialize(body);
        var response = await http.PostAsync(
            url.TrimEnd('/') + path,
            new StringContent(payload, Encoding.UTF8, "application/json"));
        var text = await response.Content.ReadAsStringAsync();
        var node = response.IsSuccessStatusCode ? JsonNode.Parse(text) as JsonObject : null;
        return (
            response.IsSuccessStatusCode,
            node?["token"]?.GetValue<string>() ?? "",
            node?["refresh"]?.GetValue<string>() ?? "");
    }

    public Task SettingsCheckAsync(string path)
    {
        var store = new SettingsStore();
        var original = store.Load();
        var probe = new AppSettings
        {
            BridgeUrl = "http://127.0.0.1:8901",
            UseBridge = true,
            ModelProvider = "lan",
            ModelBaseUrl = "http://127.0.0.1:11434/v1",
            Tone = "neutral",
        };
        store.Save(probe);
        var loaded = store.Load();
        var settingsOk = loaded.BridgeUrl == probe.BridgeUrl
            && loaded.UseBridge
            && loaded.ModelProvider == "lan"
            && loaded.Tone == "neutral";

        CredentialStore.Write("hopa.test.secret", "s3cret");
        var secretOk = CredentialStore.Read("hopa.test.secret") == "s3cret";
        CredentialStore.Delete("hopa.test.secret");
        var gone = CredentialStore.Read("hopa.test.secret").Length == 0;

        store.Save(original);
        File.WriteAllLines(path, new[]
        {
            $"settings round-trip: {(settingsOk ? "ok" : "FAIL")}",
            $"credential round-trip: {(secretOk ? "ok" : "FAIL")}",
            $"credential delete: {(gone ? "ok" : "FAIL")}",
            $"settings file: {store.FilePath}",
        });
        Application.Current.Shutdown();
        return Task.CompletedTask;
    }

    private async Task ConnectAsync()
    {
        connecting ??= ConnectCoreAsync();
        await connecting;
    }

    private async Task ConnectCoreAsync()
    {
        try
        {
            var started = new SidecarClient();
            started.LogReceived += line => Log($"sidecar: {line}");
            started.DeviceRequested += OnDeviceRequested;
            started.ApprovalRequested += OnApprovalRequested;
            client = started;
            StatusText.Text = "Ready";
            await RefreshAsync();
            await ApplySettingsAsync();
        }
        catch (Exception error)
        {
            StatusText.Text = error.Message;
            Log($"error: {error.Message}");
        }
    }

    private async Task RefreshAsync()
    {
        if (client == null)
        {
            return;
        }

        var items = await client.ListConversationsAsync();
        allConversations.Clear();
        foreach (var item in items)
        {
            allConversations.Add(ToVm(item));
        }

        if (allConversations.Count == 0)
        {
            var created = await client.CreateConversationAsync();
            if (created != null)
            {
                allConversations.Add(ToVm(created));
            }
        }

        ApplyFilter();
        if (allConversations.Count > 0)
        {
            await SelectAsync(allConversations[0].Id);
        }
    }

    private async Task SelectAsync(string id)
    {
        if (client == null || currentId == id)
        {
            return;
        }

        if (currentId != null)
        {
            drafts[currentId] = Composer.Text;
        }

        var open = await client.OpenConversationAsync(id);
        currentId = id;
        messages.Clear();
        if (open?["messages"] is JsonArray list)
        {
            foreach (var node in list)
            {
                if (node is JsonObject message)
                {
                    messages.Add(new MessageVm
                    {
                        Role = message["role"]?.GetValue<string>() ?? "assistant",
                        Text = message["text"]?.GetValue<string>() ?? "",
                    });
                }
            }
        }

        Composer.Text = drafts.TryGetValue(id, out var draft) ? draft : "";
        TitleText.Text = open?["title"]?.GetValue<string>() ?? "New chat";
        EmptyState.Visibility = messages.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        switching = true;
        ConversationList.SelectedItem = conversations.FirstOrDefault(item => item.Id == id)
            ?? allConversations.FirstOrDefault(item => item.Id == id);
        switching = false;
        ScrollToEnd();
        StatusText.Text = "";
    }

    private async Task SendAsync(string goal)
    {
        if (client == null || running)
        {
            return;
        }

        running = true;
        SendButton.IsEnabled = false;
        StatusText.Text = "Working...";
        messages.Add(new MessageVm { Role = "user", Text = goal });
        EmptyState.Visibility = Visibility.Collapsed;
        ScrollToEnd();
        try
        {
            var (answer, conversationId, title, steps, chainOk) = await client.StartTurnAsync(goal, currentId);
            currentId = conversationId;
            messages.Add(new MessageVm { Role = "assistant", Text = answer });
            Log($"HOPA: {answer}");
            lastAnswer = answer;
            CopyButton.IsEnabled = answer.Length > 0;
            SpeakButton.IsEnabled = answer.Length > 0;
            if (!IsActive)
            {
                var preview = answer.Length > 140 ? answer[..140] + "..." : answer;
                tray?.ShowBalloonTip(4000, "HOPA", preview, System.Windows.Forms.ToolTipIcon.Info);
            }
            lastSteps = steps;
            lastChainOk = chainOk;
            DetailsButton.IsEnabled = steps is { Count: > 0 };
            foreach (var line in StepLines(steps))
            {
                Log($"step {line}");
            }

            if (steps is { Count: > 0 })
            {
                Log($"chain: {(chainOk ? "ok" : "failed")}");
            }

            TitleText.Text = title;
            UpsertConversation(conversationId, title);
            ScrollToEnd();
            StatusText.Text = "";
        }
        catch (Exception error)
        {
            StatusText.Text = error.Message;
            Log($"error: {error.Message}");
        }
        finally
        {
            running = false;
            SendButton.IsEnabled = true;
        }
    }

    private async Task SendFromComposerAsync()
    {
        var goal = Composer.Text.Trim();
        if (goal.Length == 0)
        {
            return;
        }

        if (currentId != null)
        {
            drafts[currentId] = "";
        }

        Composer.Text = "";
        await SendAsync(goal);
    }

    private void UpsertConversation(string id, string title)
    {
        var existing = allConversations.FirstOrDefault(item => item.Id == id);
        if (existing != null)
        {
            existing.Title = title;
            existing.UpdatedLabel = "now";
            return;
        }

        var created = new ConversationVm { Id = id, Title = title, UpdatedLabel = "now" };
        allConversations.Insert(0, created);
        if (SearchBox.Text.Trim().Length == 0)
        {
            conversations.Insert(0, created);
        }
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void ApplyFilter()
    {
        var query = SearchBox.Text.Trim();
        conversations.Clear();
        foreach (var conversation in allConversations)
        {
            if (query.Length == 0 ||
                conversation.SearchText.Contains(query, StringComparison.OrdinalIgnoreCase))
            {
                conversations.Add(conversation);
            }
        }
    }

    private async void Rename_Click(object sender, RoutedEventArgs e)
    {
        if (client == null || ConversationList.SelectedItem is not ConversationVm vm)
        {
            return;
        }

        var dialog = new RenameWindow(vm.Title) { Owner = this };
        if (dialog.ShowDialog() != true || dialog.TitleValue.Length == 0)
        {
            return;
        }

        var renamed = await client.RenameConversationAsync(vm.Id, dialog.TitleValue);
        if (renamed?["title"]?.GetValue<string>() is { } title)
        {
            vm.Title = title;
            TitleText.Text = title;
        }
    }

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (client == null || ConversationList.SelectedItem is not ConversationVm vm)
        {
            return;
        }

        if (MessageBox.Show($"Delete \"{vm.Title}\"?", "HOPA", MessageBoxButton.OKCancel,
                MessageBoxImage.Question) != MessageBoxResult.OK)
        {
            return;
        }

        if (!await client.DeleteConversationAsync(vm.Id))
        {
            return;
        }

        allConversations.Remove(vm);
        ApplyFilter();
        if (currentId == vm.Id)
        {
            currentId = null;
            messages.Clear();
            if (allConversations.Count > 0)
            {
                await SelectAsync(allConversations[0].Id);
            }
            else
            {
                await RefreshAsync();
            }
        }
    }

    private async void Export_Click(object sender, RoutedEventArgs e)
    {
        if (client == null || ConversationList.SelectedItem is not ConversationVm vm)
        {
            return;
        }

        var json = await client.ExportConversationAsync(vm.Id);
        var dialog = new SaveFileDialog
        {
            FileName = $"{vm.Title}.json",
            Filter = "JSON|*.json",
        };
        if (dialog.ShowDialog() == true)
        {
            File.WriteAllText(dialog.FileName, json);
        }
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        if (lastAnswer.Length > 0)
        {
            Clipboard.SetText(lastAnswer);
            StatusText.Text = "Copied";
        }
    }

    private void Speak_Click(object sender, RoutedEventArgs e)
    {
        if (lastAnswer.Length == 0)
        {
            return;
        }

        speech ??= new SpeechSynthesizer();
        speech.SpeakAsyncCancelAll();
        speech.SpeakAsync(lastAnswer);
        StatusText.Text = "Reading aloud";
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        if ((Keyboard.Modifiers & ModifierKeys.Control) != 0)
        {
            switch (e.Key)
            {
                case Key.N:
                    NewChat_Click(this, new RoutedEventArgs());
                    e.Handled = true;
                    break;
                case Key.F:
                case Key.K:
                    SearchBox.Focus();
                    SearchBox.SelectAll();
                    e.Handled = true;
                    break;
            }
        }

        if (e.Key == Key.Escape)
        {
            speech?.SpeakAsyncCancelAll();
        }

        base.OnPreviewKeyDown(e);
    }

    private async void Settings_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new SettingsWindow { Owner = this };
        if (dialog.ShowDialog() == true)
        {
            await ApplySettingsAsync();
            StatusText.Text = "Settings saved";
        }
    }

    private async Task ApplySettingsAsync()
    {
        if (client == null)
        {
            return;
        }

        try
        {
            var settings = new SettingsStore().Load();
            await client.ConfigureAsync(
                settings,
                CredentialStore.Read("hopa.bridge.token"),
                CredentialStore.Read("hopa.model.key"));
        }
        catch (Exception error)
        {
            StatusText.Text = error.Message;
            Log($"error: {error.Message}");
        }
    }

    private async void NewChat_Click(object sender, RoutedEventArgs e)    {
        if (client == null)
        {
            return;
        }

        var created = await client.CreateConversationAsync();
        if (created == null)
        {
            return;
        }

        var vm = ToVm(created);
        allConversations.Insert(0, vm);
        SearchBox.Clear();
        ApplyFilter();
        currentId = null;
        await SelectAsync(vm.Id);
    }

    private async void ConversationList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (switching || ConversationList.SelectedItem is not ConversationVm vm)
        {
            return;
        }

        await SelectAsync(vm.Id);
    }

    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        await SendFromComposerAsync();
    }

    private async void Composer_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && (Keyboard.Modifiers & ModifierKeys.Shift) == 0)
        {
            e.Handled = true;
            await SendFromComposerAsync();
        }
    }

    private void OnApprovalRequested(JsonObject message)
    {
        if (autoApproval != null)
        {
            var allowed = autoApproval == "allow";
            Log($"approval: auto-{(allowed ? "allow" : "deny")} {message["title"]?.GetValue<string>()}");
            client?.SendApprovalResult(message["id"]?.GetValue<string>() ?? "", allowed);
            return;
        }

        Dispatcher.Invoke(() =>
        {
            var allow = false;
            var dialog = new ApprovalWindow(message) { Owner = this };
            if (dialog.ShowDialog() == true)
            {
                allow = true;
            }

            Log($"approval: {(allow ? "allow" : "deny")} {message["title"]?.GetValue<string>()}");
            client?.SendApprovalResult(message["id"]?.GetValue<string>() ?? "", allow);
        });
    }

    private void Details_Click(object sender, RoutedEventArgs e)
    {
        if (lastSteps == null)
        {
            return;
        }

        var lines = StepLines(lastSteps).ToList();
        if (lines.Count == 0)
        {
            lines.Add("No steps: the answer came without an action.");
        }
        else
        {
            lines.Add($"chain: {(lastChainOk ? "ok" : "failed")}");
        }

        new StepsWindow(lines) { Owner = this }.ShowDialog();
    }

    private static IEnumerable<string> StepLines(JsonArray? steps)
    {
        if (steps == null)
        {
            yield break;
        }

        foreach (var node in steps)
        {
            if (node is not JsonObject step)
            {
                continue;
            }

            var number = step["step"]?.GetValue<int>() ?? 0;
            var kind = step["action"]?["kind"]?.GetValue<string>() ?? "?";
            var classification = step["classification"]?.GetValue<string>() ?? "?";
            var approved = step["approved"]?.GetValue<bool?>();
            var executed = step["executed"]?.GetValue<bool>() == true;
            var verified = step["verified"]?.GetValue<bool>() == true;
            var error = step["error"]?.GetValue<string>() ?? "";
            var hash = step["hash"]?.GetValue<string>() ?? "";
            yield return $"#{number} {kind} [{classification}] approved={approved} " +
                         $"executed={executed} verified={verified} error={error} " +
                         $"hash={hash[..Math.Min(10, hash.Length)]}";
        }
    }

    private void OnDeviceRequested(JsonObject message)
    {
        var id = message["id"]?.GetValue<string>() ?? "";
        var op = message["op"]?.GetValue<string>() ?? "";
        if (client == null)
        {
            return;
        }

        try
        {
            if (op == "observe")
            {
                var (observation, nodes) = device.Observe();
                Log($"device: observed {nodes} nodes in '{observation["app"]}'");
                client.SendDeviceResult(id, true, observation: observation);
                return;
            }

            if (op == "foreground")
            {
                var app = device.ForegroundApp();
                client.SendDeviceResult(id, true, app: app);
                return;
            }

            var info = device.Act(op, message);
            Log($"device: {info}");
            client.SendDeviceResult(id, true);
        }
        catch (Exception error)
        {
            Log($"device error: {error.Message}");
            client.SendDeviceResult(id, false, error.Message);
        }
    }

    private void ScrollToEnd()
    {
        Dispatcher.Invoke(() => MessagesScroll.ScrollToEnd());
    }

    private void Log(string line)
    {
        lock (log)
        {
            log.Add(line);
        }
    }

    private void WriteLog()
    {
        if (outputPath == null)
        {
            return;
        }

        lock (log)
        {
            File.WriteAllLines(outputPath, log);
        }
    }

    private static ConversationVm ToVm(JsonObject item)
    {
        var updatedAt = item["updatedAt"]?.GetValue<long>() ?? 0;
        var title = item["title"]?.GetValue<string>() ?? "New chat";
        var searchParts = new List<string> { title };
        if (item["messages"] is JsonArray messages)
        {
            foreach (var node in messages)
            {
                if (node?["text"]?.GetValue<string>() is { Length: > 0 } text)
                {
                    searchParts.Add(text);
                }
            }
        }

        return new ConversationVm
        {
            Id = item["id"]?.GetValue<string>() ?? "",
            Title = title,
            UpdatedLabel = FormatTime(updatedAt),
            MessageCount = (item["messages"] as JsonArray)?.Count ?? 0,
            SearchText = string.Join("\n", searchParts),
        };
    }

    private static string FormatTime(long millis)
    {
        if (millis <= 0)
        {
            return "";
        }

        var time = DateTimeOffset.FromUnixTimeMilliseconds(millis).LocalDateTime;
        return time.Date == DateTime.Today ? time.ToString("HH:mm") : time.ToString("dd MMM");
    }
}
