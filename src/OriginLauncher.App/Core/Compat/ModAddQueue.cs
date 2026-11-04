namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// The Mods page's one-at-a-time rule for copying jars in (ModImporter), shared by the Windows, Mac and Linux pages:
/// one import runs at a time, and anything the player asks for meanwhile (more jars dropped or picked, a "Replace"
/// answered) waits here and runs next, in the order asked. Before 2026-11-04 a Replace pressed during an add was
/// dropped (its row vanished, nothing was replaced), and jars dropped during a Replace waited for an add that might
/// never come. Pure state, UI thread only (no locks).
/// </summary>
public sealed class ModAddQueue
{
    /// <summary>One import: jars to add (Files), or one confirmed replace (Replace).</summary>
    public sealed record Work(IReadOnlyList<string>? Files, ReplaceOffer? Replace);

    private readonly List<Work> _waiting = new();

    /// <summary>An import is copying right now.</summary>
    public bool Busy { get; private set; }

    /// <summary>Something is waiting for the running import.</summary>
    public bool HasWaiting => _waiting.Count > 0;

    /// <summary>
    /// Starts <paramref name="work"/> now (true: the caller runs it and then calls <see cref="Finish"/>), or queues it
    /// behind the running import (false). Jars queued back to back go in as one batch, as one drop would.
    /// </summary>
    public bool TryStart(Work work)
    {
        if (!Busy)
        {
            Busy = true;
            return true;
        }
        if (work.Files != null && _waiting.Count > 0 && _waiting[^1].Files is { } last)
            _waiting[^1] = new Work(last.Concat(work.Files).ToList(), null);
        else
            _waiting.Add(work);
        return false;
    }

    /// <summary>
    /// The running import ended. Returns the next waiting work (the caller starts it with <see cref="TryStart"/>), or
    /// null. <paramref name="stillSameVersion"/> false: the page moved to another version meanwhile, and what waited
    /// was for the old one, so it is dropped.
    /// </summary>
    public Work? Finish(bool stillSameVersion)
    {
        Busy = false;
        if (!stillSameVersion)
        {
            _waiting.Clear();
            return null;
        }
        if (_waiting.Count == 0) return null;
        var next = _waiting[0];
        _waiting.RemoveAt(0);
        return next;
    }

    /// <summary>The page was reset (another version or profile): nothing waiting carries over.</summary>
    public void ClearWaiting() => _waiting.Clear();
}
