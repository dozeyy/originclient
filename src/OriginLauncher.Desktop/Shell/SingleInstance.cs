using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace OriginLauncher.Desktop.Shell;

// One launcher at a time, and opening Origin again brings the open one forward (the Windows launcher's single-instance
// mutex + FocusExistingInstance). The lock (DesktopEntry: an exclusive lock on .launcher.lock in the data folder) says
// WHO is first; this is the "come to the front" half: the first launcher listens on a Unix socket next to that lock
// (or, when that path is too long for a socket address, in $XDG_RUNTIME_DIR, named after the data folder), and a second
// start sends it one fixed word and exits. The socket is the player's own (mode 0600) and only that word is acted on.
internal static class SingleInstance
{
    private const string Word = "activate";
    private const int MaxSocketPath = 100; // sun_path is 108 bytes; keep a margin

    /// <summary>Where the first launcher listens: beside the lock, or in the runtime folder when that is too long.</summary>
    public static string SocketPath(string dataRoot, string? runtimeDir)
    {
        var beside = Path.Combine(dataRoot, ".launcher.sock");
        if (Encoding.UTF8.GetByteCount(beside) <= MaxSocketPath || string.IsNullOrEmpty(runtimeDir)) return beside;
        var tag = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(dataRoot)))[..12].ToLowerInvariant();
        return Path.Combine(runtimeDir, "origin-launcher-" + tag + ".sock");
    }

    /// <summary>A second start: asks the running launcher to come forward. True when it took the message.</summary>
    public static bool TrySignal(string path)
    {
        try
        {
            using var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            socket.SendTimeout = 2000;
            var connect = socket.ConnectAsync(new UnixDomainSocketEndPoint(path));
            if (!connect.Wait(TimeSpan.FromSeconds(2))) return false;
            socket.Send(Encoding.ASCII.GetBytes(Word + "\n"));
            socket.Shutdown(SocketShutdown.Send);
            return true;
        }
        catch (Exception)
        {
            return false; // an older launcher without the socket, or it is just closing
        }
    }

    /// <summary>The first launcher: listens for the rest of its life (a background thread); each "activate" runs
    /// <paramref name="onActivate"/> on the UI thread. Fails quietly where the folder can't hold a socket.</summary>
    public static void Listen(string path, Action onActivate)
    {
        Socket listener;
        try
        {
            // We hold the lock, so a socket file left here is a crashed launcher's: replace it.
            if (File.Exists(path)) File.Delete(path);
            listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            listener.Bind(new UnixDomainSocketEndPoint(path));
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            listener.Listen(4);
        }
        catch (Exception ex)
        {
            OriginLauncher.App.Core.StartupTrace.Mark("single-instance socket unavailable: " + ex.Message);
            return;
        }
        var thread = new Thread(() =>
        {
            var buffer = new byte[32];
            while (true)
            {
                try
                {
                    using var client = listener.Accept();
                    client.ReceiveTimeout = 2000;
                    var read = client.Receive(buffer);
                    if (Encoding.ASCII.GetString(buffer, 0, read).Trim() == Word)
                        Avalonia.Threading.Dispatcher.UIThread.Post(onActivate);
                }
                catch (SocketException) { /* a client that hung up or timed out: wait for the next */ }
                catch (ObjectDisposedException) { return; }
            }
        }) { IsBackground = true, Name = "Origin single instance" };
        thread.Start();
    }
}
