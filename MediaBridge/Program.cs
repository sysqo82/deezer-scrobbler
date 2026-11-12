using System;
using System.Threading;
using System.Threading.Tasks;
using Windows.Media.Control;

// Long-lived bridge: connects to GSMTC once, reacts to change events and writes one line per state change.
// Exits when stdin closes (parent app exited).
class Program {
    static GlobalSystemMediaTransportControlsSessionManager? manager;
    static readonly SemaphoreSlim signal = new(0);
    static Task<GlobalSystemMediaTransportControlsSessionMediaProperties>? pendingProps;
    static string? pendingAppId;
    static volatile bool gaveUpWaiting;
    static string? lastLine;

    static void Main(string[] args) {
        new Thread(() => {
            try { while (Console.In.ReadLine() != null) { } } catch { }
            Environment.Exit(0);
        }) { IsBackground = true }.Start();

        try {
            Run().GetAwaiter().GetResult();
        } catch (Exception ex) {
            Write($"ERROR|{ex.Message}");
        }
        Environment.Exit(1);
    }

    static async Task Run() {
        var requestTask = GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask();
        if (await Task.WhenAny(requestTask, Task.Delay(10000)) != requestTask) {
            Write("ERROR|GSMTC session manager did not respond");
            return;
        }
        manager = await requestTask;
        manager.SessionsChanged += (_, _) => { Subscribe(); signal.Release(); };
        Subscribe();
        signal.Release();

        while (true) {
            await signal.WaitAsync();
            await Task.Delay(300); // coalesce bursts of events
            while (signal.CurrentCount > 0) await signal.WaitAsync();
            Write(await Evaluate());
        }
    }

    static readonly object subscribeLock = new();
    static System.Collections.Generic.List<GlobalSystemMediaTransportControlsSession> subscribed = new();

    static void OnPropsChanged(GlobalSystemMediaTransportControlsSession s, MediaPropertiesChangedEventArgs e) => signal.Release();
    static void OnPlaybackChanged(GlobalSystemMediaTransportControlsSession s, PlaybackInfoChangedEventArgs e) => signal.Release();

    static void Subscribe() {
        lock (subscribeLock) {
            foreach (var s in subscribed) {
                try { s.MediaPropertiesChanged -= OnPropsChanged; s.PlaybackInfoChanged -= OnPlaybackChanged; } catch { }
            }
            subscribed = new(manager!.GetSessions());
            foreach (var s in subscribed) {
                s.MediaPropertiesChanged += OnPropsChanged;
                s.PlaybackInfoChanged += OnPlaybackChanged;
            }
        }
    }

    static GlobalSystemMediaTransportControlsSession? FindDeezerSession() {
        GlobalSystemMediaTransportControlsSession? playing = null;
        foreach (var s in manager!.GetSessions()) {
            var appId = s.SourceAppUserModelId ?? "";
            if (appId.Contains("deezer", StringComparison.OrdinalIgnoreCase) ||
                appId.Contains("electron", StringComparison.OrdinalIgnoreCase)) {
                return s;
            }
            if (playing == null && s.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) {
                playing = s;
            }
        }
        return playing;
    }

    static async Task<string> Evaluate() {
        var session = FindDeezerSession();
        if (session == null) return "DEEZER_NOT_FOUND";

        // Never issue a second properties request to a session while a previous one is still unanswered.
        var appId = session.SourceAppUserModelId ?? "";
        if (pendingProps == null || pendingAppId != appId) {
            pendingProps = session.TryGetMediaPropertiesAsync().AsTask();
            pendingAppId = appId;
            gaveUpWaiting = false;
            // Re-evaluate once a slow reply finally arrives (only if we stopped waiting for it).
            _ = pendingProps.ContinueWith(_ => { if (gaveUpWaiting) signal.Release(); });
        }
        if (await Task.WhenAny(pendingProps, Task.Delay(5000)) != pendingProps) {
            gaveUpWaiting = true;
            return lastLine ?? "DEEZER_NOT_FOUND";
        }
        var propsTask = pendingProps;
        pendingProps = null;
        pendingAppId = null;
        if (propsTask.IsFaulted) return lastLine ?? "DEEZER_NOT_FOUND";

        var props = propsTask.Result;
        var status = session.GetPlaybackInfo()?.PlaybackStatus.ToString() ?? "Unknown";
        return $"DEEZER|{props?.Artist ?? ""}|{props?.Title ?? ""}|{props?.AlbumTitle ?? ""}|{status}";
    }

    static void Write(string line) {
        if (line == lastLine) return;
        lastLine = line;
        Console.WriteLine(line);
        Console.Out.Flush();
    }
}
