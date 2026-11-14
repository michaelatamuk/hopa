namespace Hopa.Windows;

/// <summary>Non-secret preferences; keys live in Windows Credential Manager.</summary>
public sealed class AppSettings
{
    public string BridgeUrl { get; set; } = "";

    public bool UseBridge { get; set; }

    public string ModelProvider { get; set; } = "openai";

    public string ModelBaseUrl { get; set; } = "https://api.openai.com/v1";

    public string ModelName { get; set; } = "gpt-4o-mini";

    public string Tone { get; set; } = "warm";

    public double WindowLeft { get; set; } = double.NaN;

    public double WindowTop { get; set; } = double.NaN;

    public double WindowWidth { get; set; } = 1140;

    public double WindowHeight { get; set; } = 760;

    public bool WindowMaximized { get; set; }
}
