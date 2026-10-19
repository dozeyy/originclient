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
    public List<JournalEntry> Entries { get; set; } = new();

    /// <summary>Things a rollback could not put back (one sentence each); empty when it was clean.</summary>
    public List<string> RollbackProblems { get; set; } = new();
}

public static class ImportJournalStore
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = false,
        Converters = { new JsonStringEnumConverter() },
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static void Save(ImportJournal journal, string path) =>
        AtomicFile.WriteAllText(path, JsonSerializer.Serialize(journal, Options));

    /// <summary>The journal at <paramref name="path"/>, or null when it is missing or unreadable.</summary>
    public static ImportJournal? Load(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            return JsonSerializer.Deserialize<ImportJournal>(File.ReadAllText(path), Options);
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }
}
