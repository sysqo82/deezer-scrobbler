using System;
using System.Threading;
using System.Threading.Tasks;
using Windows.Media.Control;

class Program {
    static async Task Main(string[] args) {
        using var cts = new CancellationTokenSource(1500);
        try {
            var task = GetMediaInfoAsync();
            if (await Task.WhenAny(task, Task.Delay(1400, cts.Token)) == task) {
                Console.WriteLine(await task);
            } else {
                Console.WriteLine("DEEZER_NOT_FOUND");
            }
        } catch (Exception ex) {
            Console.WriteLine($"ERROR|{ex.Message}");
        }
    }

    static async Task<string> GetMediaInfoAsync() {
        var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
        var sessions = manager.GetSessions();
        foreach (var session in sessions) {
            var appId = session.SourceAppUserModelId ?? "";
            if (appId.Contains("Deezer", StringComparison.OrdinalIgnoreCase)) {
                var props = await session.TryGetMediaPropertiesAsync();
                var info = session.GetPlaybackInfo();
                var status = info.PlaybackStatus.ToString();
                return $"DEEZER|{props.Artist}|{props.Title}|{props.AlbumTitle}|{status}";
            }
        }
        return "DEEZER_NOT_FOUND";
    }
}
