using System.IO;

namespace OriginLauncher.App.Core.Import;

/// <summary>
/// One apply (or undo) at a time per Origin game folder, process-wide, and a record of when each folder last took a
/// finished import or undo.
///
/// Why: two applies into the same folder at once both saw "World1 isn't there yet", both journaled it as created, and
/// whichever lost the race rolled back by DELETING the copy the other had just committed (proven by
/// ImportStressEdgeTests.TheSameImportStartedTwiceAtOnceNeverDamagesTheOneThatFinished: both runs rolled back). The
/// UI only lets one import run, but the engine is the last line of defence, so it serializes the writers itself.
///
/// A plan is a snapshot of the folder: after waiting for another import, or after one finished since the plan was
/// made, its "World1 isn't there" may be untrue. <see cref="ChangedSince"/> lets the apply refuse such a plan before it
/// changes anything; planning again sees the new state (and leaves what is already there alone).
///
/// Order rule (no deadlocks): folder locks are taken BEFORE any version gate, always in sorted order, all at once.
/// </summary>
internal static class ImportTargetLocks
{
    private static readonly object Sync = new();
    private static readonly Dictionary<string, SemaphoreSlim> Locks = new(StringComparer.OrdinalIgnoreCase);
    private static readonly Dictionary<string, long> LastChange = new(StringComparer.OrdinalIgnoreCase);
    private static long _clock;

    /// <summary>The stamp a plan records when planning starts.</summary>
    public static long Now()
    {
        lock (Sync) return _clock;
    }

    /// <summary>A finished import or undo changed this folder after <paramref name="stamp"/>.</summary>
    public static bool ChangedSince(IEnumerable<string> dirs, long stamp)
    {
        lock (Sync)
            return dirs.Where(d => d.Length > 0).Any(d => LastChange.TryGetValue(Key(d), out var at) && at > stamp);
    }

    public static void MarkChanged(IEnumerable<string> dirs)
    {
        lock (Sync)
        {
            var at = ++_clock;
            foreach (var dir in dirs.Where(d => d.Length > 0)) LastChange[Key(dir)] = at;
        }
    }

    /// <summary>Waits (cancellable) until no other apply or undo holds any of <paramref name="dirs"/>; dispose to release.</summary>
    public static IDisposable Acquire(IEnumerable<string> dirs, CancellationToken ct)
    {
        var keys = dirs.Where(d => d.Length > 0).Select(Key).Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(k => k, StringComparer.OrdinalIgnoreCase).ToList();
        var held = new List<SemaphoreSlim>();
        try
        {
            foreach (var key in keys)
            {
                SemaphoreSlim gate;
                lock (Sync)
                {
                    if (!Locks.TryGetValue(key, out gate!)) Locks[key] = gate = new SemaphoreSlim(1, 1);
                }
                gate.Wait(ct);
                held.Add(gate);
            }
        }
        catch
        {
            Release(held);
            throw;
        }
        return new Held(held);
    }

    private static void Release(List<SemaphoreSlim> held)
    {
        for (var i = held.Count - 1; i >= 0; i--) held[i].Release();
        held.Clear();
    }

    private static string Key(string dir) => Path.TrimEndingDirectorySeparator(Path.GetFullPath(dir));

    private sealed class Held : IDisposable
    {
        private List<SemaphoreSlim>? _held;
        public Held(List<SemaphoreSlim> held) => _held = held;

        public void Dispose()
        {
            var held = Interlocked.Exchange(ref _held, null);
            if (held != null) Release(held);
        }
    }

    /// <summary>The sentence an apply returns when its plan is older than the folder it would change. Nothing was changed.</summary>
    public const string StalePlan =
        "Another import into this Minecraft finished after this one was planned, so nothing was changed. Open the import again to see what's still left to bring over.";
}
