using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using NAudio.Wave;

namespace Hopa.Windows;

/// <summary>
/// W4: microphone capture, the bridge's `/transcribe` and `/speak`, and WAV
/// playback. Capture needs a microphone; the HTTP half works without one.
/// </summary>
public sealed class VoiceClient : IDisposable
{
    private static readonly WaveFormat CaptureFormat = new(16000, 16, 1);

    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(90) };
    private WaveInEvent? capture;
    private MemoryStream? pcm;
    private WaveOutEvent? playback;

    public bool Recording => capture != null;

    public void StartCapture()
    {
        if (capture != null)
        {
            return;
        }

        pcm = new MemoryStream();
        capture = new WaveInEvent
        {
            WaveFormat = CaptureFormat,
            BufferMilliseconds = 100,
        };
        capture.DataAvailable += (_, args) => pcm?.Write(args.Buffer, 0, args.BytesRecorded);
        capture.StartRecording();
    }

    public byte[] StopCapture()
    {
        var stream = pcm;
        var recorder = capture;
        capture = null;
        pcm = null;
        if (recorder == null || stream == null)
        {
            return Array.Empty<byte>();
        }

        recorder.StopRecording();
        recorder.Dispose();
        var pcmBytes = stream.ToArray();
        stream.Dispose();
        return ToWav(pcmBytes, CaptureFormat);
    }

    public async Task<string> TranscribeAsync(string baseUrl, string token, byte[] wav)
    {
        var payload = new JsonObject { ["audio"] = Convert.ToBase64String(wav) };
        var response = await PostAsync(baseUrl, "/transcribe", token, payload);
        return response?["text"]?.GetValue<string>() ?? "";
    }

    public async Task<byte[]?> SpeakAsync(string baseUrl, string token, string text)
    {
        var payload = new JsonObject { ["text"] = text };
        var response = await PostAsync(baseUrl, "/speak", token, payload);
        var audio = response?["audio"]?.GetValue<string>();
        return string.IsNullOrEmpty(audio) ? null : Convert.FromBase64String(audio);
    }

    public void Play(byte[] wav)
    {
        playback?.Stop();
        playback?.Dispose();
        playback = new WaveOutEvent();
        var reader = new WaveFileReader(new MemoryStream(wav));
        playback.Init(reader);
        playback.Play();
    }

    public void StopPlayback()
    {
        playback?.Stop();
    }

    public void Dispose()
    {
        capture?.Dispose();
        playback?.Dispose();
        http.Dispose();
    }

    private async Task<JsonObject?> PostAsync(
        string baseUrl,
        string path,
        string token,
        JsonObject body,
        bool allowRefresh = true)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, baseUrl.TrimEnd('/') + path)
        {
            Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"),
        };
        if (!string.IsNullOrEmpty(token))
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }

        using var response = await http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        if (response.StatusCode == System.Net.HttpStatusCode.Unauthorized && allowRefresh)
        {
            var refreshed = await RefreshAsync(baseUrl);
            if (refreshed != null)
            {
                return await PostAsync(baseUrl, path, refreshed, body, allowRefresh: false);
            }
        }

        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"bridge {path} returned {(int)response.StatusCode}: {text}");
        }

        return JsonNode.Parse(text) as JsonObject;
    }

    /// <summary>One `/refresh` with the stored token pair, persisted on success.</summary>
    private async Task<string?> RefreshAsync(string baseUrl)
    {
        var refresh = CredentialStore.Read("hopa.bridge.refresh");
        if (refresh.Length == 0)
        {
            return null;
        }

        var request = new HttpRequestMessage(HttpMethod.Post, baseUrl.TrimEnd('/') + "/refresh")
        {
            Content = new StringContent(
                new JsonObject { ["refresh"] = refresh }.ToJsonString(),
                Encoding.UTF8,
                "application/json"),
        };
        using var response = await http.SendAsync(request);
        if (!response.IsSuccessStatusCode)
        {
            return null;
        }

        var node = JsonNode.Parse(await response.Content.ReadAsStringAsync()) as JsonObject;
        var token = node?["token"]?.GetValue<string>() ?? "";
        var next = node?["refresh"]?.GetValue<string>() ?? "";
        if (token.Length == 0)
        {
            return null;
        }

        CredentialStore.Write("hopa.bridge.token", token);
        CredentialStore.Write("hopa.bridge.refresh", next);
        return token;
    }

    /// <summary>PCM in, a 44-byte RIFF/WAVE container out.</summary>
    private static byte[] ToWav(byte[] pcm, WaveFormat format)
    {
        using var output = new MemoryStream();
        using var writer = new BinaryWriter(output, Encoding.ASCII, leaveOpen: true);
        var blockAlign = (short)(format.Channels * format.BitsPerSample / 8);
        var byteRate = format.SampleRate * blockAlign;
        writer.Write(Encoding.ASCII.GetBytes("RIFF"));
        writer.Write(36 + pcm.Length);
        writer.Write(Encoding.ASCII.GetBytes("WAVE"));
        writer.Write(Encoding.ASCII.GetBytes("fmt "));
        writer.Write(16);
        writer.Write((short)1);
        writer.Write((short)format.Channels);
        writer.Write(format.SampleRate);
        writer.Write(byteRate);
        writer.Write(blockAlign);
        writer.Write((short)format.BitsPerSample);
        writer.Write(Encoding.ASCII.GetBytes("data"));
        writer.Write(pcm.Length);
        writer.Write(pcm);
        writer.Flush();
        return output.ToArray();
    }
}
