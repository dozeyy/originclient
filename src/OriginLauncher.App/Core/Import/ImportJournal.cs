using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import;

public static class JournalEntryKinds
{
    public const string Created = "created";
    public const string Replaced = "replaced";
    public const string SettingsDelta = "settings-delta";

    /// <summary>Only in the append-only entries log: withdraws the last "created" file entry with this path (ImportTransaction.ForgetNewFile).</summary>
    public const string Forget = "forget";
}

/// <summary>
/// One reversible step. created: the path did not exist (undo deletes it);
/// replaced: the path held a file that was moved to Backup first (undo puts it
/// back); settings-delta: the launcher settings change (undo reverts it).
/// Paths are absolute so a recovery needs nothing but this file.
/// </summary>
public sealed class JournalEntry
{
    public string Kind { get; set; } = "";
    public string? Path { get; set; }
    public bool IsDirectory { get; set; }

    /// <summary>For a created folder: true when the import filled it (undo deletes it with everything in it); false for a folder made only to hold something (undo removes it only if empty).</summary>
    public bool Recursive { get; set; }
    public string? Backup { get; set; }
    public SettingsDelta? Delta { get; set; }

    /// <summary>A reused Mojang game file (or a folder made to hold them): once the import has finished, undo leaves it in place (it is the verified file Origin would download anyway, and may be in use by now).</summary>
    public bool GameFile { get; set; }
}

// imports/<id>/journal.json. Written atomically BEFORE each change it describes
// (write-ahead), so whatever state a crash leaves, the journal names everything
// that may have been touched. Undo is always "reverse the entries"; a journal
// still Applying at the next launch is rolled back, never resumed (ImportRecovery).
public sealed class ImportJournal
{
    public string ImportId { get; set; } = "";
    public ImportState State { get; set; } = ImportState.Planned;
    public DateTime StartedUtc { get; set; }
    public DateTime? FinishedUtc { get; set; }
    public string? SourceName { get; set; }
    public string? TargetVersion { get; set; }

    /// <summary>Origin's game folder the import wrote into (undo of a finished import refuses while a game runs from it).</summary>
    public string? TargetGameDir { get; set; }

    // ---- a bulk import ("import everything") writes into several versions in ONE run; the single fields above
    // stay for journals written before (and for single imports). ----

    /// <summary>Every Minecraft version a bulk run wrote into (null for a single import).</summary>
    public List<string>? TargetVersions { get; set; }

    /// <summary>Every Origin game folder a bulk run wrote into: undo refuses while a game runs from any of them.</summary>
    public List<string>? TargetGameDirs { get; set; }

    /// <summary>How many setups a bulk run imported (null for a single import).</summary>
    public int? SetupCount { get; set; }

    /// <summary>The versions whose provisioning gate the bulk apply held, in the order it took them (ascending): undo takes the same.</summary>
    public List<string>? GateVersions { get; set; }

    /// <summary>
    /// Set (and saved, with State = Applying) just before the undo of a FINISHED import starts: if that undo is cut
    /// short, ImportRecovery finishes it with the same rule (reused game files stay) instead of removing them.
    /// </summary>
    public bool UndoOfCommitted { get; set; }

    // ---- the versions' SHARED mods folders (instances/<version>/mods): every account of a version plays that one
    // folder, so another account's import can count on a jar this run added. ----

    /// <summary>The shared mods folders this run wrote into: the undo takes their writer locks, like the apply did.</summary>
    public List<string>? SharedModsDirs { get; set; }

    /// <summary>
    /// Jars of a shared mods folder this run found already there and counted on instead of copying its own ("already
    /// in Origin's mods"). The undo of an EARLIER import that added one of them keeps its changes to that folder.
    /// </summary>
    public List<string> SharedModsReliedOn { get; set; } = new();

    /// <summary>
    /// Set by the undo of a FINISHED import, saved before anything moves (recovery finishes a cut-short undo with the
    /// same rule): shared mods folders whose changes stay, because a later import counts on a jar this one added.
    /// Undoing them would take a mod away from that import's account.
    /// </summary>
    public List<string>? KeepSharedModsDirs { get; set; }

    // ---- profiles (instances/<version>/profiles/<id>): every imported setup's mods land in a profile of their own. ----

    /// <summary>
    /// The mods folders of the profiles this run MADE. Every account of the version can play a profile, so the undo of a
    /// finished run waits until none of that version's games is running.
    /// </summary>
    public List<string> ProfileModsDirs { get; set; } = new();

    /// <summary>
    /// Profiles (ids) this run found already holding exactly its mods and reused instead of making a second copy. The
    /// undo of the EARLIER import that made one keeps that profile (its files and its settings entry).
    /// </summary>
    public List<string> ProfilesReliedOn { get; set; } = new();

    /// <summary>True for <paramref name="path"/> being one of <paramref name="dirs"/> or directly inside one.</summary>
    public static bool InDirs(string? path, IEnumerable<string>? dirs)
    {
        if (path == null || dirs == null) return false;
        var full = System.IO.Path.TrimEndingDirectorySeparator(path);
        var parent = System.IO.Path.GetDirectoryName(full);
        foreach (var d in dirs)
        {
            var dir = System.IO.Path.TrimEndingDirectorySeparator(d);
            if (string.Equals(dir, full, StringComparison.OrdinalIgnoreCase)
                || (parent != null && string.Equals(dir, parent, StringComparison.OrdinalIgnoreCase)))
                return true;
        }
        return false;
    }

    public List<JournalEntry> Entries { get; set; } = new();

    /// <summary>
    /// The append-only file (in the run folder) holding the entries written since this snapshot, one JSON line each;
    /// null when the snapshot has them all (journals written before the log existed never name one).
    /// </summary>
    public string? EntriesLog { get; set; }

    /// <summary>Things a rollback could not put back (one sentence each); empty when it was clean.</summary>
    public List<string> RollbackProblems { get; set; } = new();
}

// journal.json is a SNAPSHOT (the header and the entries up to the last state change); entries added since are
// APPENDED, one JSON line each, to the log the snapshot names (EntriesLog). Rewriting the whole journal before every
// op made a big run quadratic (a bulk import journals ~10k entries); appending keeps it linear while every entry is
// still on disk before its op runs. Load = snapshot + its log (a torn last line, from a crash mid-write, is the entry
// of an op that never ran: it is dropped). A state change (begin, commit, rollback) writes a new snapshot with every
// entry and starts a new log, so a finished journal is a single file again. Old journals (no log) load as they are.
public static class ImportJournalStore
{
    internal static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = false,
        Converters = { new JsonStringEnumConverter() },
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    /// <summary>Writes the snapshot atomically (as it is: <see cref="ImportJournal.EntriesLog"/> included). Returns the bytes written.</summary>
    public static long Save(ImportJournal journal, string path)
    {
        var text = JsonSerializer.Serialize(journal, Options);
        AtomicFile.WriteAllText(path, text);
        return System.Text.Encoding.UTF8.GetByteCount(text);
    }

    /// <summary>One entry as the line the entries log holds (no newline).</summary>
    internal static string Line(JournalEntry entry) => JsonSerializer.Serialize(entry, Options);

    /// <summary>The journal at <paramref name="path"/> with its appended entries, or null when it is missing or unreadable.</summary>
    public static ImportJournal? Load(string path)
    {
        ImportJournal? journal;
        try
        {
            if (!File.Exists(path)) return null;
            journal = JsonSerializer.Deserialize<ImportJournal>(File.ReadAllText(path), Options);
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            return null;
        }
        if (journal?.EntriesLog is { Length: > 0 } log && Path.GetFileName(log) == log)
            Replay(journal, Path.Combine(Path.GetDirectoryName(path)!, log));
        return journal;
    }

    private static void Replay(ImportJournal journal, string logPath)
    {
        string[] lines;
        try
        {
            if (!File.Exists(logPath)) return;
            using var stream = new FileStream(logPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var reader = new StreamReader(stream, System.Text.Encoding.UTF8);
            lines = reader.ReadToEnd().Split('\n');
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return; // the snapshot alone: every entry in it is still undone
        }
        foreach (var raw in lines)
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;
            JournalEntry? entry;
            try { entry = JsonSerializer.Deserialize<JournalEntry>(line, Options); }
            catch (JsonException) { break; } // a torn last line: its op never ran
            if (entry == null) continue;
            if (entry.Kind == JournalEntryKinds.Forget)
            {
                var index = journal.Entries.FindLastIndex(e => e.Kind == JournalEntryKinds.Created && !e.IsDirectory && e.Path == entry.Path);
                if (index >= 0) journal.Entries.RemoveAt(index);
                continue;
            }
            journal.Entries.Add(entry);
        }
    }
}
