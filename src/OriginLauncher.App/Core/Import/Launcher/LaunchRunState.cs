namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// True while Home is preparing a game launch: from the moment a launch is accepted until its process exists
/// (or the launch ends some other way). The import's "is a game running" probe only knows a game once its
/// process exists, so without this an import could start in the window where Minecraft is still being
/// installed or started, and the two would write the same version folder at once.
///
/// Home holds <see cref="Enter"/> across the whole launch attempt in a try/finally, so every exit path (done,
/// cancelled, failed, exception) releases it. It counts holders (one per account's launch). The import
/// backend refuses while it is set, and the panel can disable Import on <see cref="Changed"/>.
/// </summary>
public static class LaunchRunState
{
    public const string Refusal = "Wait for Minecraft to finish starting, then try again.";

    private static readonly object Gate = new();
    private static int _holders;

    public static bool IsStarting => Volatile.Read(ref _holders) > 0;

    /// <summary>Raised (on whichever thread changed it) when a launch starts or the last one ends; listeners marshal to the UI themselves.</summary>
    public static event Action? Changed;

    /// <summary>A launch is in flight until the returned guard is disposed (idempotent).</summary>
    public static IDisposable Enter()
    {
        bool changed;
        lock (Gate) changed = ++_holders == 1;
        if (changed) Changed?.Invoke();
        return new Holder();
    }

    /// <summary>
    /// The plain sentence an import shows when it cannot start because a launch is still starting; null when it may go.
    /// (It deliberately ignores ImportRunState: the panel marks an import running BEFORE it calls Apply.)
    /// </summary>
    public static string? ImportRefusal() => IsStarting ? Refusal : null;

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
