using System.IO;
using System.IO.Pipes;
using System.Security.Cryptography;
using System.Text;

namespace OriginLauncher.App.Core.Import.Launcher;

// `Origin.exe --import` while a launcher is already running: the second process cannot open the import card
// itself (the single-instance guard ends it), so it passes ONE fixed word to the running launcher over a named
// pipe and exits. The pipe is scoped to the current user (CurrentUserOnly on both ends, plus the user name in
// the pipe name) and the only thing ever accepted is the literal command "import": anything else, anything
// longer, a client that stalls, is ignored. The command carries no data and no path.
//
// Pure of WPF: the server just calls a callback on a pool thread; the launcher marshals it to the UI.
public static class ImportIpc
{
    public const string Command = "import";

    /// <summary>Longest line the server reads before giving up on a client (the command plus its newline fits many times over).</summary>
    public const int MaxLineBytes = 16;

    /// <summary>How long the server waits for a connected client to finish its one line.</summary>
    public static readonly TimeSpan ReadTimeout = TimeSpan.FromSeconds(2);

    /// <summary>
    /// The pipe name for one launcher flavour (<paramref name="instanceKey"/> is the single-instance mutex name, which
    /// already differs per developer build) and one Windows user.
    /// </summary>
    public static string PipeName(string instanceKey, string? userName = null)
    {
        var basis = instanceKey + "|" + (userName ?? Environment.UserName);
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(basis.ToUpperInvariant())))[..24];
        return "OriginLauncher.Import." + hash;
    }

    /// <summary>True only for the exact word "import" (a trailing line ending is allowed; case and extra text are not).</summary>
    public static bool IsImportCommand(string? line)
    {
        if (line == null) return false;
        var text = line.EndsWith("\r\n", StringComparison.Ordinal) ? line[..^2]
            : line.EndsWith('\n') ? line[..^1] : line;
        return string.Equals(text, Command, StringComparison.Ordinal);
    }

    /// <summary>Sends "import" to the running launcher. False when nobody answered in time (the caller then just focuses the window).</summary>
    public static bool TrySend(string pipeName, int timeoutMs = 1500)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", pipeName, PipeDirection.Out, PipeOptions.CurrentUserOnly);
            client.Connect(timeoutMs);
            var bytes = Encoding.ASCII.GetBytes(Command + "\n");
            client.Write(bytes, 0, bytes.Length);
            client.Flush();
            return true;
        }
        catch (Exception)
        {
            // Not running yet, busy, or not ours: a courtesy path, never an error for the user.
            return false;
        }
    }

    /// <summary>Reads at most <see cref="MaxLineBytes"/> bytes up to the first newline; null when it runs on or stalls.</summary>
    internal static async Task<string?> ReadLineAsync(Stream stream, CancellationToken ct)
    {
        var buffer = new byte[MaxLineBytes];
        var used = 0;
        while (used < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(used), ct).ConfigureAwait(false);
            if (read == 0) return Encoding.ASCII.GetString(buffer, 0, used); // the client closed: whatever it sent is the line
            used += read;
            var newline = Array.IndexOf(buffer, (byte)'\n', 0, used);
            if (newline >= 0) return Encoding.ASCII.GetString(buffer, 0, newline + 1);
        }
        return null; // full without a newline: not our protocol
    }
}

/// <summary>The running launcher's end of <see cref="ImportIpc"/>: accepts one client at a time on the thread pool.</summary>
public sealed class ImportIpcServer : IDisposable
{
    private readonly string _pipeName;
    private readonly Action _onImport;
    private readonly CancellationTokenSource _stop = new();

    public ImportIpcServer(string pipeName, Action onImport)
    {
        _pipeName = pipeName;
        _onImport = onImport;
    }

    public void Start() => _ = Task.Run(() => LoopAsync(_stop.Token));

    private async Task LoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var pipe = new NamedPipeServerStream(_pipeName, PipeDirection.In, 1,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await pipe.WaitForConnectionAsync(ct).ConfigureAwait(false);
                using var read = CancellationTokenSource.CreateLinkedTokenSource(ct);
                read.CancelAfter(ImportIpc.ReadTimeout);
                var line = await ImportIpc.ReadLineAsync(pipe, read.Token).ConfigureAwait(false);
                if (ImportIpc.IsImportCommand(line)) _onImport();
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception)
            {
                // A stalled or broken client, or the pipe name briefly unavailable: wait a moment and listen again.
                try { await Task.Delay(250, ct).ConfigureAwait(false); }
                catch (OperationCanceledException) { return; }
            }
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
    }
}
