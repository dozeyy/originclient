using System.Collections.Concurrent;

namespace OriginLauncher.App.Core.GameFolders;

/// <summary>
/// The "your packs weren't copied" sentence a first Play adds to its running line (AccountSeed's notice), kept per game
/// folder until that launch takes it. Each launch's sentence is its own entry: a cancelled launch forgets only the one
/// it put there, never a newer launch's for the same folder (two launches of one account overlap for a moment when the
/// newest cancels the older one).
/// </summary>
public sealed class SeedNotices
{
    // A class, not the string: two launches with the same sentence must still be told apart.
    public sealed class Entry
    {
        internal Entry(string text) => Text = text;
        public string Text { get; }
    }

    private readonly ConcurrentDictionary<string, Entry> _byDir = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Records this launch's sentence for the folder (a newer one replaces an older one) and returns it.</summary>
    public Entry Set(string gameDir, string text)
    {
        var entry = new Entry(text);
        _byDir[gameDir] = entry;
        return entry;
    }

    /// <summary>The folder's sentence, once; null when there is none.</summary>
    public string? Take(string gameDir) => _byDir.TryRemove(gameDir, out var entry) ? entry.Text : null;

    /// <summary>Drops <paramref name="entry"/> if it is still the folder's sentence (a cancelled launch's own).</summary>
    public void Forget(string gameDir, Entry? entry)
    {
        if (entry != null) _byDir.TryRemove(new KeyValuePair<string, Entry>(gameDir, entry));
    }
}
