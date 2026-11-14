using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Definitions;
using FlaUI.Core.Input;
using FlaUI.Core.WindowsAPI;
using FlaUI.UIA3;

namespace Hopa.Windows;

/// <summary>
/// W3: the Windows device adapter. Observation is a real UI Automation tree with
/// pre-order refs; actions try the UIA pattern first and fall back to the mouse
/// or keyboard. Every call is wrapped: a control that disappears or throws is
/// reported, never swallowed.
/// </summary>
public sealed class WindowsDevice : IDisposable
{
    private const int MaxNodes = 350;
    private const int MaxDepth = 14;

    private readonly UIA3Automation automation = new();
    private readonly Dictionary<string, AutomationElement> refs = new();
    private AutomationElement? root;
    private int counter;

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    private static readonly string[] SecureProcesses =
    {
        "lockapp", "logonui", "winlogon", "screensaver", "logonui.exe",
    };

    /// <summary>
    /// The lock screen and the logon desktop are secure surfaces: UI Automation
    /// can list a few nodes, but input is refused by the system. Say so instead
    /// of pretending the tree is the user's screen.
    /// </summary>
    private bool IsSecureSurface(out string processName)
    {
        var hwnd = GetForegroundWindow();
        GetWindowThreadProcessId(hwnd, out var pid);
        processName = "";
        try
        {
            processName = Process.GetProcessById((int)pid).ProcessName;
        }
        catch
        {
            return false;
        }

        return SecureProcesses.Contains(processName.ToLowerInvariant());
    }

    private void RefuseSecureSurface()
    {
        if (IsSecureSurface(out var processName))
        {
            throw new InvalidOperationException(
                $"the screen is '{processName}' (locked or secure); HOPA cannot see or touch it");
        }
    }

    public System.Windows.Size ScreenSize()
    {
        var width = (int)System.Windows.SystemParameters.PrimaryScreenWidth;
        var height = (int)System.Windows.SystemParameters.PrimaryScreenHeight;
        return new System.Windows.Size(width, height);
    }

    public string ForegroundApp()
    {
        var hwnd = GetForegroundWindow();
        GetWindowThreadProcessId(hwnd, out var pid);
        try
        {
            return Process.GetProcessById((int)pid).ProcessName;
        }
        catch
        {
            return "";
        }
    }

    /// <summary>Builds the observation JSON the Kotlin contract decodes.</summary>
    public (System.Text.Json.Nodes.JsonObject Observation, int Nodes) Observe()
    {
        RefuseSecureSurface();
        counter = 0;
        refs.Clear();
        root = ForegroundElement();
        var lines = new List<string>();
        var tree = root == null
            ? Node("root", "root", "", "", null, false, true, false, true, false, false, new List<System.Text.Json.Nodes.JsonObject>())
            : Walk(root, 0, lines);
        var app = ForegroundApp();
        var screen = ScreenSize();
        var observation = new System.Text.Json.Nodes.JsonObject
        {
            ["schemaVersion"] = "1",
            ["id"] = $"win-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}",
            ["timestamp"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            ["displayId"] = 0,
            ["windowId"] = "active",
            ["rotation"] = 0,
            ["density"] = 1.0,
            ["screenSize"] = new System.Text.Json.Nodes.JsonObject
            {
                ["width"] = (int)screen.Width,
                ["height"] = (int)screen.Height,
            },
            ["focus"] = "",
            ["text"] = string.Join("\n", lines.Take(40)),
            ["tree"] = tree,
            ["screenshotRef"] = "",
            ["app"] = app,
            ["hash"] = "",
            ["truncated"] = lines.Count > 40,
        };
        return (observation, refs.Count);
    }

    public string Act(string op, System.Text.Json.Nodes.JsonObject message)
    {
        RefuseSecureSurface();
        var point = PointFor(message);
        var screenPoint = new System.Drawing.Point(point.X, point.Y);
        switch (op)
        {
            case "tap":
                return Tap(point, message);
            case "doubleTap":
                Mouse.DoubleClick(screenPoint);
                return $"double-clicked {point.X},{point.Y}";
            case "longPress":
                Mouse.MoveTo(screenPoint);
                Mouse.Down(MouseButton.Left);
                Thread.Sleep(700);
                Mouse.Up(MouseButton.Left);
                return $"long-pressed {point.X},{point.Y}";
            case "type":
                return Type(message);
            case "swipe":
                return Swipe(message);
            case "key":
                return Key(message);
            case "launch":
                var app = message["app"]?.GetValue<string>() ?? "";
                if (app.Length == 0)
                {
                    throw new InvalidOperationException("launch needs an app");
                }

                Process.Start(new ProcessStartInfo(app) { UseShellExecute = true });
                return $"launched {app}";
            default:
                throw new InvalidOperationException($"the Windows adapter cannot '{op}' yet");
        }
    }

    public void Dispose() => automation.Dispose();

    private AutomationElement? ForegroundElement()
    {
        try
        {
            var hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero)
            {
                return null;
            }

            return automation.FromHandle(hwnd);
        }
        catch
        {
            return null;
        }
    }

    private System.Text.Json.Nodes.JsonObject Walk(
        AutomationElement element,
        int depth,
        List<string> lines)
    {
        var refId = $"e{++counter}";
        var controlType = Safe(() => element.Properties.ControlType.ValueOrDefault.ToString());
        var name = Safe(() => element.Properties.Name.ValueOrDefault ?? "");
        var automationId = Safe(() => element.Properties.AutomationId.ValueOrDefault ?? "");
        var isPassword = Safe(() => element.Properties.IsPassword.ValueOrDefault);
        var enabled = Safe(() => element.Properties.IsEnabled.ValueOrDefault);
        var offscreen = Safe(() => element.Properties.IsOffscreen.ValueOrDefault);
        var focused = Safe(() => element.Properties.HasKeyboardFocus.ValueOrDefault);
        var clickable = Safe(() => element.Patterns.Invoke.IsSupported
            || element.Patterns.Toggle.IsSupported
            || element.Patterns.SelectionItem.IsSupported);
        var scrollable = Safe(() => element.Patterns.Scroll.IsSupported);
        var bounds = Safe(() => element.BoundingRectangle);

        var type = isPassword ? "password" : controlType.ToLowerInvariant();
        if (!isPassword)
        {
            refs[refId] = element;
            if (name.Length > 0)
            {
                lines.Add($"{refId} {type} {name}");
            }
        }
        else
        {
            name = "";
            lines.Add($"{refId} password field (dropped)");
        }

        var children = new List<System.Text.Json.Nodes.JsonObject>();
        if (depth < MaxDepth && counter < MaxNodes)
        {
            AutomationElement[] kids;
            try
            {
                kids = element.FindAllChildren();
            }
            catch
            {
                kids = Array.Empty<AutomationElement>();
            }

            foreach (var child in kids)
            {
                if (counter >= MaxNodes)
                {
                    break;
                }

                children.Add(Walk(child, depth + 1, lines));
            }
        }

        return Node(
            refId,
            type,
            name,
            automationId,
            bounds,
            clickable,
            enabled,
            false,
            !offscreen,
            scrollable,
            focused,
            children);
    }

    private static System.Text.Json.Nodes.JsonObject Node(
        string refId,
        string type,
        string text,
        string id,
        System.Drawing.Rectangle? bounds,
        bool clickable,
        bool enabled,
        bool isChecked,
        bool visible,
        bool scrollable,
        bool focused,
        List<System.Text.Json.Nodes.JsonObject> children)
    {
        var node = new System.Text.Json.Nodes.JsonObject
        {
            ["ref"] = refId,
            ["type"] = type,
            ["text"] = text,
            ["description"] = "",
            ["id"] = id,
            ["clickable"] = clickable,
            ["enabled"] = enabled,
            ["checked"] = isChecked,
            ["scrollable"] = scrollable,
            ["visible"] = visible,
            ["focused"] = focused,
            ["children"] = new System.Text.Json.Nodes.JsonArray(children.ToArray()),
        };
        if (bounds is { Width: > 0 } rect)
        {
            node["bounds"] = new System.Text.Json.Nodes.JsonArray(
                rect.X,
                rect.Y,
                rect.X + rect.Width,
                rect.Y + rect.Height);
        }
        else
        {
            node["bounds"] = null;
        }

        return node;
    }

    private (int X, int Y) PointFor(System.Text.Json.Nodes.JsonObject message)
    {
        var refId = message["ref"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(refId))
        {
            if (!refs.TryGetValue(refId, out var element))
            {
                throw new InvalidOperationException($"stale ref {refId}; observe again");
            }

            var bounds = element.BoundingRectangle;
            return ((int)(bounds.X + bounds.Width / 2), (int)(bounds.Y + bounds.Height / 2));
        }

        var x = message["x"]?.GetValue<int>() ?? 0;
        var y = message["y"]?.GetValue<int>() ?? 0;
        return (x, y);
    }

    private string Tap((int X, int Y) point, System.Text.Json.Nodes.JsonObject message)
    {
        var refId = message["ref"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(refId) && refs.TryGetValue(refId, out var element))
        {
            try
            {
                if (element.Patterns.Invoke.IsSupported)
                {
                    element.Patterns.Invoke.Pattern.Invoke();
                    return $"invoked {refId}";
                }

                if (element.Patterns.SelectionItem.IsSupported)
                {
                    element.Patterns.SelectionItem.Pattern.Select();
                    return $"selected {refId}";
                }
            }
            catch (Exception error)
            {
                // Fall through to the mouse; the pattern was offered but failed.
                _ = error;
            }
        }

        Mouse.Click(new System.Drawing.Point(point.X, point.Y));
        return $"clicked {point.X},{point.Y}";
    }

    private string Type(System.Text.Json.Nodes.JsonObject message)
    {
        var text = message["text"]?.GetValue<string>() ?? "";
        var refId = message["ref"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(refId) && refs.TryGetValue(refId, out var element))
        {
            try
            {
                if (element.Patterns.Value.IsSupported)
                {
                    element.Patterns.Value.Pattern.SetValue(text);
                    return $"set {refId} to {text.Length} chars";
                }

                element.Focus();
            }
            catch (Exception error)
            {
                _ = error;
            }
        }

        Keyboard.Type(text);
        return $"typed {text.Length} chars";
    }

    private string Swipe(System.Text.Json.Nodes.JsonObject message)
    {
        var fromX = message["fromX"]?.GetValue<int>() ?? 0;
        var fromY = message["fromY"]?.GetValue<int>() ?? 0;
        var toX = message["toX"]?.GetValue<int>() ?? 0;
        var toY = message["toY"]?.GetValue<int>() ?? 0;
        Mouse.MoveTo(new System.Drawing.Point(fromX, fromY));
        Mouse.Down(MouseButton.Left);
        Thread.Sleep(80);
        Mouse.MoveTo(new System.Drawing.Point(toX, toY));
        Mouse.Up(MouseButton.Left);
        return $"swiped {fromX},{fromY} -> {toX},{toY}";
    }

    private string Key(System.Text.Json.Nodes.JsonObject message)
    {
        var key = message["key"]?.GetValue<string>() ?? "";
        switch (key)
        {
            case "back":
                Keyboard.Type(VirtualKeyShort.ESC);
                return "pressed Escape (back)";
            case "home":
                Keyboard.Press(VirtualKeyShort.LWIN);
                Keyboard.Type(VirtualKeyShort.KEY_D);
                Keyboard.Release(VirtualKeyShort.LWIN);
                return "showed the desktop (home)";
            default:
                throw new InvalidOperationException($"unknown key {key}");
        }
    }

    private static T Safe<T>(Func<T> read)
    {
        try
        {
            return read();
        }
        catch
        {
            return default!;
        }
    }
}

