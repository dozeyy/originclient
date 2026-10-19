using System.IO;

namespace OriginLauncher.App.Core.Import;

// Where one import run keeps its bookkeeping: imports/<importId>/
//   journal.json  what the run created or replaced (so it can be undone)
//   backup/       every file the run replaced, moved here first
//   staging/      big trees (worlds, folder packs) are copied here, then renamed in
//   import.log    every action by relative path — never file contents, never a token
//   summary.txt   the plain-language result the player reads
// Pure path rules over a root the caller passes in (the launcher's imports folder in the
// app, a temp folder in tests).
public static class ImportLayout
{
    public const string JournalFile = "journal.json";
    public const string BackupFolder = "backup";
    public const string StagingFolder = "staging";
    public const string LogFile = "import.log";
    public const string SummaryFile = "summary.txt";

    /// <summary>Backups of this many of the newest committed imports are kept.</summary>
    public const int KeepBackupsOf = 3;

    public static string RunDir(string importsRoot, string importId) => Path.Combine(importsRoot, importId);
    public static string Journal(string importsRoot, string importId) => Path.Combine(RunDir(importsRoot, importId), JournalFile);
    public static string Backup(string importsRoot, string importId) => Path.Combine(RunDir(importsRoot, importId), BackupFolder);
    public static string Staging(string importsRoot, string importId) => Path.Combine(RunDir(importsRoot, importId), StagingFolder);
    public static string Log(string importsRoot, string importId) => Path.Combine(RunDir(importsRoot, importId), LogFile);
    public static string Summary(string importsRoot, string importId) => Path.Combine(RunDir(importsRoot, importId), SummaryFile);

    /// <summary>True for an id this code made (32 lower-case hex): checked before an id is used as a folder name.</summary>
    public static bool IsValidId(string? id) =>
        id is { Length: 32 } && id.All(c => c is (>= '0' and <= '9') or (>= 'a' and <= 'f'));
}
