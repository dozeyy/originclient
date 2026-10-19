namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// True from the moment an update download starts until the launcher is gone (a successful update never
/// releases it: the process is restarting). The import side reads it: no import is opened or applied while it
/// is set, so the restart can never kill one half done. The other direction (an import already running when the
/// update is about to restart) is <see cref="PrepareToRestartAsync"/>: the restart waits for it.
/// </summary>
public static class UpdateRunState
{
    public const string Refusal = "Origin is restarting to update. Try the import again after it reopens.";

    private static readonly object Gate = new();
    private static int _holders;

    public static bool IsRunning => Volatile.Read(ref _holders) > 0;

    /// <summary>
    /// Tests only (the test project compiles this file): back to idle with no listeners, so one failed test can never
    /// leave the process-wide state busy, or a stray listener attached, for the next.
    /// </summary>
    internal static void ResetForTests()
    {
        lock (Gate) _holders = 0;
        Changed = null;
    }

    /// <summary>Raised (on whichever thread changed it) when an update starts or the last one ends without restarting.</summary>
    public static event Action? Changed;

    /// <summary>An update is in flight until the guard is disposed (idempotent). On success the caller never disposes it.</summary>
    public static IDisposable Enter()
    {
        bool changed;
        lock (Gate) changed = ++_holders == 1;
        if (changed) Changed?.Invoke();
        return new Holder();
    }

    /// <summary>
    /// The last step before the launcher restarts for an update: when an import is still writing to the game
    /// folders, say so once (<paramref name="onWaiting"/>) and wait for it to finish. Shutdown must come after
    /// this returns, on the same (UI) thread, with nothing awaited in between.
    /// </summary>
    public static async Task PrepareToRestartAsync(Action onWaiting, CancellationToken ct = default)
    {
        if (!ImportRunState.IsApplying) return;
        onWaiting();
        await ImportRunState.WhenIdleAsync(ct);
    }

    private static void Release()
    {
        bool changed;
        lock (Gate)
        {
            if (_holders == 0) return;
            changed = --_holders == 0;
        }
        if (changed) Changed?.Invoke();
    }

    private sealed class Holder : IDisposable
    {
        private int _released;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _released, 1) == 0) Release();
        }
    }
}
