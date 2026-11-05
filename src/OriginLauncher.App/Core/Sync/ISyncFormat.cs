namespace OriginLauncher.App.Core.Sync;

/// <summary>The instance a value is being pushed into.</summary>
public sealed record SyncInstance(string Version, string Directory);

// One file format Settings Sync understands. Each format turns its file into a
// flat key -> raw value map (Read), decides when two raw values mean the same
// setting (Equivalent — e.g. options.txt's `ao:2` and `ao:true`), and writes the
// store's values back into a target file while leaving everything else in it
// untouched (Apply).
public interface ISyncFormat
{
    /// <summary>key -> raw value, or null when the file is unreadable/corrupt (it is then skipped, never overwritten).</summary>
    Dictionary<string, string>? Read(string path);

    /// <summary>True when a value should never enter the store (excluded key, or a value that could break a loader).</summary>
    bool Rejects(string key, string raw);

    /// <summary>True when this version's value of a key is its own: never taken into the store, never overwritten from it.</summary>
    bool StaysIn(string key, string version) => false;

    bool Equivalent(string key, string a, string b);

    /// <summary>What the baseline remembers for a value (the value itself, or a hash for whole files).</summary>
    string BaselineOf(string raw);

    bool MatchesBaseline(string key, string raw, string baseline);

    /// <summary>
    /// Whether a key that vanished from a file is a real deletion. False for
    /// every format whose loader can drop keys it doesn't know (vanilla,
    /// Sodium, Iris): there a missing key just means "this version doesn't have it".
    /// </summary>
    bool AllowsDeletions { get; }

    /// <summary>
    /// A key the game writes a NEW value into whenever it deliberately replaces the whole
    /// file (applying a mod profile rewrites originclient-mods.json to exactly that profile).
    /// When it changed, many keys vanishing at once are the player's choice, not a failed
    /// load, so they are carried as deletions. null: the format has no such marker.
    /// </summary>
    string? WholeReplaceMarker => null;

    /// <summary>
    /// A subtree whose children are entries the player creates and deletes as a unit
    /// (originclient-mods.json's "/profiles": one saved mod profile is ~165 keys). Keys that
    /// vanish only from such entries are deletions even when they are many, and an entry
    /// left with no values after a push is removed whole (an empty profile would still be
    /// listed). null: the format has none.
    /// </summary>
    string? WholeEntrySubtree => null;

    /// <summary>
    /// The values the game restarts this file from when it can't read it (originclient-mods.json: ModsConfig renames a
    /// damaged file .corrupt and starts again from the bundled default-mods-config.json), flattened like
    /// <see cref="Read"/>. Lets Settings Sync tell that restart from the player deleting every entry at once: a restart
    /// puts keys back to these values, a player's own edits don't. null: unknown.
    /// </summary>
    IReadOnlyDictionary<string, string>? Starter => null;

    /// <summary>
    /// Writes the store's view into the target file. A null value in
    /// <paramref name="desired"/> is a deletion. Returns the file's resulting
    /// key map (the new baseline), or null when the file was skipped.
    /// Never writes when nothing changes.
    /// </summary>
    Dictionary<string, string>? Apply(string path, IReadOnlyDictionary<string, string?> desired, SyncInstance target);
}
