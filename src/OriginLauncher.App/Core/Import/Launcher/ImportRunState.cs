namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// True exactly while something is writing to the player's game folders for an import: an import applying,
/// or the startup recovery rolling an interrupted one back. Home reads it to keep Play and Join disabled for
/// that time (a launch would race the writes); it re-enables them when it ends.
///
/// It counts holders, so a recovery and an import that overlap can never clear each other's "busy".
/// Prefer <see cref="Enter"/> (a using): the End can then not be skipped by an exception between a Begin and
/// its try. <see cref="Begin"/>/<see cref="End"/> stay for older callers; an extra End never goes below zero.
/// </summary>
public static class ImportRunState
{
    private static readonly object Gate = new();
    private static int _holders;

    public static bool IsApplying => Volatile.Read(ref _holders) > 0;

    /// <summary>Raised (on whichever thread changed it) when it goes busy or idle; listeners marshal to the UI themselves.</summary>
    public static event Action? Changed;

    /// <summary>Busy until the returned guard is disposed (idempotent). <c>using var run = ImportRunState.Enter();</c></summary>
    public static IDisposable Enter()
    {
        Begin();
        return new Holder();
    }

    public static void Begin()
    {
        bool changed;
        lock (Gate) changed = ++_holders == 1;
        if (changed) Changed?.Invoke();
    }

    public static void End()
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
            if (Interlocked.Exchange(ref _released, 1) == 0) End();
        }
    }
}
