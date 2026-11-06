using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Windows.Media.Control;

class Program {
    [STAThread]
    static void Main(string[] args) {
        MainAsync(args).GetAwaiter().GetResult();
    }

    static async Task MainAsync(string[] args) {
        using var cts = new CancellationTokenSource(2500);
        try {
            var task = GetMediaInfoAsync();
            if (await Task.WhenAny(task, Task.Delay(2400, cts.Token)) == task) {
                Console.WriteLine(await task);
            } else {
                Console.WriteLine("DEEZER_NOT_FOUND");
            }
        } catch (Exception ex) {
            Console.WriteLine($"ERROR|{ex.Message}");
        } finally {
            Environment.Exit(0);
        }
    }

    static async Task<string> GetMediaInfoAsync() {
        var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        var sessions = manager.GetSessions();
        foreach (var session in sessions) {
            var appId = session.SourceAppUserModelId ?? "";
            var props = await session.TryGetMediaPropertiesAsync();
            var info = session.GetPlaybackInfo();
            var status = info?.PlaybackStatus.ToString() ?? "Unknown";
            var artist = props?.Artist ?? "";
            var title = props?.Title ?? "";
            var album = props?.AlbumTitle ?? "";

            if (appId.Contains("deezer", StringComparison.OrdinalIgnoreCase) ||
                title.Contains("deezer", StringComparison.OrdinalIgnoreCase) ||
                artist.Contains("deezer", StringComparison.OrdinalIgnoreCase) ||
                appId.Contains("electron", StringComparison.OrdinalIgnoreCase)) {
                return $"DEEZER|{artist}|{title}|{album}|{status}";
            }
        }

        // Fallback: if any active session is playing, return it
        foreach (var session in sessions) {
            var info = session.GetPlaybackInfo();
            if (info?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) {
                var props = await session.TryGetMediaPropertiesAsync();
                var status = info.PlaybackStatus.ToString();
                var artist = props?.Artist ?? "";
                var title = props?.Title ?? "";
                var album = props?.AlbumTitle ?? "";
                if (!string.IsNullOrEmpty(title)) {
                    return $"DEEZER|{artist}|{title}|{album}|{status}";
                }
            }
        }

        return "DEEZER_NOT_FOUND";
    }
}
