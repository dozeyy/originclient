using System.Collections.Concurrent;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Presets;

/// <summary>
/// One account's 1.8.9 game folder, and whether that account's game is up. The phase is a delegate, read again
/// right before every write, so a game that starts between the click and the write is still refused.
/// </summary>
public sealed record GamePresetTarget(string GameDir, Func<GamePhase> Phase)
{
    /// <summary>The real account: its folder from OriginPaths and its phase from the launch book (1.8.9 only).</summary>
    public static GamePresetTarget ForAccount(AccountLaunchBook book, string accountKey) =>
        new(OriginPaths.GameDir(GamePresetOptions.SupportedVersion, accountKey),
            () => book.VersionOf(accountKey) == GamePresetOptions.SupportedVersion ? book.PhaseOf(accountKey) : GamePhase.Idle);
}

/// <summary>
/// Previews, applies and undoes a 1.8.9 preset for one account (design D1, docs/overhaul/audit/architecture.md s6).
/// The launcher writes the files; the game is never running while it does.
///
/// Rules:
///  * Only on the player's click (the UI calls Apply); never on launch.
///  * Refuses while that account's 1.8.9 game is starting or open: Minecraft rewrites options.txt when it quits,
///    which would undo the write (or half-undo it).
///  * Writes only the keys whose value differs; every other line, its order and unknown keys stay as they are.
///  * The new undo record is written FIRST under a pending name (origin-preset-undo.json.new), then each file
///    atomically (temp file + rename), and only when every write succeeded does the pending record replace the
///    real one. A refusal or a failed write therefore never costs the player an earlier, still valid undo; a
///    failed write puts the already-written files back.
///  * options.txt and optionsof.txt are read and written as Latin-1: Minecraft 1.8.9 uses the JVM default
///    charset (Cp1252 on Windows), so a non-ASCII pack or server name is not UTF-8. Latin-1 maps every byte to
///    one char and back, so those files round-trip byte for byte. Origin's JSON config is UTF-8 (Gson).
///  * One Apply / Undo at a time per game folder (a double click waits for the first to finish).
///  * Origin's own settings are written only when config/originclient-mods.json exists: the mod seeds a fresh
///    install from its bundled defaults and skips that when the file exists, so a file we created with a few keys
///    would throw the seed away.
///
/// Every method does file IO: call from a background task, not the UI thread. None throws for IO problems; they
/// come back as a <see cref="GamePresetResult"/> with a plain-English message.
/// </summary>
public static class GamePresetWriter
{
    private const int UndoSchema = 1;
    private const string PendingSuffix = ".new";
    private static readonly Encoding Latin1 = Encoding.Latin1;
    private static readonly UTF8Encoding Utf8 = new(false);
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> Gates = new(StringComparer.OrdinalIgnoreCase);
    private static readonly GamePresetFile[] FileOrder = { GamePresetFile.Options, GamePresetFile.OptiFine, GamePresetFile.Origin };

    // ------------------------------------------------------------------ public API

    /// <summary>What Apply would change right now: key, current value, new value. Writes nothing.</summary>
    public static GamePresetPreview Preview(GamePresetTarget account, GamePresetKind kind)
    {
        var blocked = Blocker(account);
        if (blocked != null) return new GamePresetPreview(Array.Empty<GamePresetChange>(), Array.Empty<string>(), blocked);
        var plan = MakePlan(ReadStates(account), kind);
        return new GamePresetPreview(plan.Changes, plan.Notes, plan.Problem);
    }

    /// <summary>Writes the preset. See the class comment for the rules.</summary>
    public static GamePresetResult Apply(GamePresetTarget account, GamePresetKind kind)
    {
        var gate = GateFor(account.GameDir);
        gate.Wait();
        try { return ApplyCore(account, kind); }
        finally { gate.Release(); }
    }

    private static GamePresetResult ApplyCore(GamePresetTarget account, GamePresetKind kind)
    {
        try
        {
            if (Blocker(account) is { } blocked) return GamePresetResult.Fail(Refusal(account) ? GamePresetStatus.Refused : GamePresetStatus.Failed, blocked);

            var states = ReadStates(account);
            var plan = MakePlan(states, kind);
            if (plan.Problem != null) return GamePresetResult.Fail(GamePresetStatus.Failed, plan.Problem);
            if (plan.Changes.Count == 0)
                return new GamePresetResult(GamePresetStatus.NothingToChange, $"{kind} is already set. Nothing to change.", plan.Changes);

            var touched = states.Values.Where(s => s.Touches.Count > 0).OrderBy(s => Array.IndexOf(FileOrder, s.File)).ToList();
            foreach (var s in touched) s.NewText = s.Doc!.ToText();

            // 1. The new undo record under its pending name, before anything else is written. The real record
            //    (an earlier Apply's) stays untouched until every file below has been written.
            var undoPath = InstanceLayout.PresetUndoFile(account.GameDir);
            var pendingPath = undoPath + PendingSuffix;
            SettlePending(account, undoPath, pendingPath);
            KeepDamagedUndo(undoPath);
            AtomicFile.WriteAllText(pendingPath, UndoRecord.Serialize(kind, DateTimeOffset.UtcNow, touched), waitOutReaders: false);

            // 2. Each file, atomically. The game may have started since the first check: look again.
            if (Blocker(account) is { } late)
            {
                TryDelete(pendingPath);
                return GamePresetResult.Fail(GamePresetStatus.Refused, late);
            }
            var written = new List<FileState>();
            try
            {
                foreach (var s in touched)
                {
                    WriteText(s.Path, s.NewText!, s.File);
                    written.Add(s);
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                var restored = RollBack(written);
                if (restored) TryDelete(pendingPath);        // nothing changed: the earlier record (if any) stays as it was
                else Promote(pendingPath, undoPath);          // some files changed: Undo must know how
                return GamePresetResult.Fail(GamePresetStatus.Failed,
                    "Couldn't write the settings (a file is locked or read-only). "
                    + (restored ? "Nothing was changed." : "Some files may have changed: use Undo to put them back."));
            }

            // 3. Every write succeeded: the pending record becomes the real one. If the swap itself fails the
            //    pending file stays, and Undo / PendingUndo prefer it because its files were written.
            Promote(pendingPath, undoPath);

            var count = plan.Changes.Count;
            return new GamePresetResult(GamePresetStatus.Done,
                $"{kind} applied: {count} {(count == 1 ? "setting" : "settings")} changed. Undo puts every one back.", plan.Changes);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return GamePresetResult.Fail(GamePresetStatus.Failed, "Couldn't write the settings. Nothing was changed.");
        }
    }

    /// <summary>Is there something to undo, and which preset made it. Null when no valid undo record exists.</summary>
    public static GamePresetUndoInfo? PendingUndo(GamePresetTarget account)
    {
        var record = LoadCurrent(account, out _);
        return record == null ? null : new GamePresetUndoInfo(record.Kind, record.AppliedUtc);
    }

    /// <summary>Puts every key the last Apply touched back to what it was (keys that did not exist are removed).</summary>
    public static GamePresetResult Undo(GamePresetTarget account)
    {
        var gate = GateFor(account.GameDir);
        gate.Wait();
        try { return UndoCore(account); }
        finally { gate.Release(); }
    }

    private static GamePresetResult UndoCore(GamePresetTarget account)
    {
        try
        {
            if (Blocker(account) is { } blocked) return GamePresetResult.Fail(Refusal(account) ? GamePresetStatus.Refused : GamePresetStatus.Failed, blocked);

            var undoPath = InstanceLayout.PresetUndoFile(account.GameDir);
            var pendingPath = undoPath + PendingSuffix;
            if (!File.Exists(undoPath) && !File.Exists(pendingPath)) return GamePresetResult.Fail(GamePresetStatus.Failed, "There is nothing to undo.");
            var record = LoadCurrent(account, out var error);
            if (record == null)
                return GamePresetResult.Fail(GamePresetStatus.Failed,
                    "The undo information is damaged, so nothing was changed. " + error);

            // Work out every file's end state first; only then touch the disk.
            var actions = new List<(string Path, string? Text, GamePresetFile File)>(); // null text = delete the file
            var changes = new List<GamePresetChange>();
            foreach (var file in record.Files)
            {
                var path = PathOf(account, file.File);
                var plannedAction = PlanRestore(path, file, changes);
                if (plannedAction.Error != null) return GamePresetResult.Fail(GamePresetStatus.Failed, plannedAction.Error);
                if (plannedAction.Action is { } action) actions.Add(action);
            }

            foreach (var (path, text, file) in actions)
            {
                if (text == null) File.Delete(path);
                else WriteText(path, text, file);
            }
            File.Delete(undoPath);
            File.Delete(pendingPath);
            return new GamePresetResult(GamePresetStatus.Done,
                $"Undone: {record.Kind} is off again and your earlier settings are back.", changes);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return GamePresetResult.Fail(GamePresetStatus.Failed,
                "Couldn't write the settings (a file is locked or read-only). Run Undo again once it is free.");
        }
    }

    /// <summary>"Competitive", "Balanced", "Quality", or "Custom" when the files match none of them.</summary>
    public static string CurrentLabel(GamePresetTarget account)
    {
        if (!Directory.Exists(account.GameDir)) return GamePresetOptions.CustomLabel;
        foreach (var kind in Enum.GetValues<GamePresetKind>())
        {
            // A fresh read per preset: planning edits the in-memory documents.
            var plan = MakePlan(ReadStates(account), kind);
            if (plan.Problem == null && plan.Changes.Count == 0) return kind.ToString();
        }
        return GamePresetOptions.CustomLabel;
    }

    // ------------------------------------------------------------------ guards

    private static bool Refusal(GamePresetTarget account) => account.Phase() != GamePhase.Idle;

    // The reason Apply / Undo can't run right now, or null.
    private static string? Blocker(GamePresetTarget account)
    {
        switch (account.Phase())
        {
            case GamePhase.Running:
                return "Minecraft 1.8.9 is open for this account. Close the game first: it rewrites its settings when it quits and would undo the change.";
            case GamePhase.Starting:
            case GamePhase.Preparing:
                return "Minecraft 1.8.9 is starting for this account. Wait for it to open and close it, then try again.";
        }
        return Directory.Exists(account.GameDir)
            ? null
            : "This account hasn't started 1.8.9 yet. Play it once, close the game, then pick a preset.";
    }

    // ------------------------------------------------------------------ reading and planning

    private sealed class FileState
    {
        public GamePresetFile File;
        public string Path = "";
        public bool Existed;
        public string? OriginalText;
        public IKeyDoc? Doc;
        public string? Problem;   // a file the engine must not edit (damaged / unreadable)
        public bool Skipped;      // Origin config not there (yet): its keys are left out
        public string? NewText;
        public readonly List<UndoKey> Touches = new();
    }

    private sealed record Plan(IReadOnlyList<GamePresetChange> Changes, IReadOnlyList<string> Notes, string? Problem);

    private static string PathOf(GamePresetTarget account, GamePresetFile file) => file switch
    {
        GamePresetFile.Options => Path.Combine(account.GameDir, "options.txt"),
        GamePresetFile.OptiFine => Path.Combine(account.GameDir, "optionsof.txt"),
        _ => Path.Combine(account.GameDir, "config", "originclient-mods.json"),
    };

    private static string NameOf(GamePresetFile file) => file switch
    {
        GamePresetFile.Options => "options.txt",
        GamePresetFile.OptiFine => "optionsof.txt",
        _ => "originclient-mods.json",
    };

    private static Dictionary<GamePresetFile, FileState> ReadStates(GamePresetTarget account)
    {
        var states = new Dictionary<GamePresetFile, FileState>();
        foreach (var file in FileOrder)
        {
            var s = new FileState { File = file, Path = PathOf(account, file) };
            states[file] = s;
            if (!File.Exists(s.Path))
            {
                // A missing options.txt / optionsof.txt is created. The Origin config is never created (class comment).
                if (file == GamePresetFile.Origin) s.Skipped = true;
                else s.Doc = KeyValueText.Empty();
                continue;
            }
            s.Existed = true;
            try
            {
                s.OriginalText = ReadText(s.Path, file);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                s.Problem = $"{NameOf(file)} can't be read right now ({ex.Message.TrimEnd('.')}).";
                continue;
            }
            s.Doc = file == GamePresetFile.Origin ? OriginConfigJson.Parse(s.OriginalText) : KeyValueText.Parse(s.OriginalText);
            if (s.Doc != null) continue;
            // Origin's settings are secondary: a damaged config just leaves them out. The two Minecraft files block.
            if (file == GamePresetFile.Origin) s.Skipped = true;
            else s.Problem = $"{NameOf(file)} looks damaged. Start 1.8.9 once so it repairs the file, then try again.";
        }
        return states;
    }

    // Applies the preset to the in-memory documents and lists what changed. Pure given the states.
    private static Plan MakePlan(Dictionary<GamePresetFile, FileState> states, GamePresetKind kind)
    {
        var changes = new List<GamePresetChange>();
        var notes = new List<string>();
        string? problem = null;
        foreach (var s in states.Values)
            if (s.Problem != null) problem ??= s.Problem;

        foreach (var entry in GamePresetOptions.For(kind, GamePresetOptions.SupportedVersion))
        {
            var s = states[entry.File];
            if (s.Doc == null || s.Skipped || s.Problem != null) continue;
            var raw = s.Doc.Get(entry.Key);
            var effective = raw ?? entry.Default;
            if (PresetValues.Same(entry.Key, effective, entry.Value)) continue;
            s.Touches.Add(new UndoKey(entry.Key, raw != null, raw));
            s.Doc.Set(entry.Key, entry.Value);
            changes.Add(new GamePresetChange(entry.File, entry.Key, effective, entry.Value));
        }

        if (states[GamePresetFile.Origin].Skipped)
            notes.Add(states[GamePresetFile.Origin].Existed
                ? "Origin's own settings file couldn't be read, so blur, sparkles, entity distance and particle changer were left out."
                : "Origin's own settings (blur, sparkles, entity distance, particle changer) apply once this account has started 1.8.9 once.");
        if (!states[GamePresetFile.OptiFine].Existed && states[GamePresetFile.OptiFine].Problem == null)
            notes.Add("optionsof.txt doesn't exist yet. It will be created with just these settings; OptiFine fills in the rest.");
        return new Plan(changes, notes, problem);
    }

    // ------------------------------------------------------------------ undo

    private sealed record UndoKey(string Key, bool Existed, string? Old);

    private sealed record Restore(string? Error, (string Path, string? Text, GamePresetFile File)? Action);

    // The end state of one file after undo. Appends the lines it changes to <changes> (for the message).
    private static Restore PlanRestore(string path, UndoRecord.FileRecord file, List<GamePresetChange> changes)
    {
        string? current = null;
        if (File.Exists(path))
        {
            try
            {
                current = ReadText(path, file.File);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                return new Restore($"{NameOf(file.File)} can't be read right now ({ex.Message.TrimEnd('.')}). Nothing was changed.", null);
            }
        }

        foreach (var k in file.Keys)
            changes.Add(new GamePresetChange(file.File, k.Key, null, k.Old ?? "(not set)"));

        if (current == null)
            return new Restore(null, file.Existed ? (path, file.OriginalText, file.File) : null); // deleted since: put it back

        // Untouched since our write: the original, byte for byte.
        if (Sha256(current, file.File) == file.WrittenSha256)
            return new Restore(null, (path, file.Existed ? file.OriginalText : null, file.File));

        // The game (or the player) changed the file since: restore just our keys.
        IKeyDoc? doc = file.File == GamePresetFile.Origin ? OriginConfigJson.Parse(current) : KeyValueText.Parse(current);
        if (doc == null)
            return new Restore($"{NameOf(file.File)} looks damaged, so nothing was changed. Start 1.8.9 once so it repairs the file, then try Undo again.", null);
        foreach (var k in file.Keys)
        {
            if (k.Existed) doc.Set(k.Key, k.Old!);
            else doc.Remove(k.Key);
        }
        if (!file.Existed && doc is KeyValueText kv && kv.HasNoKeys) return new Restore(null, (path, null, file.File)); // we created it: gone again
        return new Restore(null, (path, doc.ToText(), file.File));
    }

    private sealed class UndoRecord
    {
        public sealed record FileRecord(GamePresetFile File, bool Existed, string? OriginalText, string WrittenSha256, List<UndoKey> Keys);

        public GamePresetKind Kind;
        public DateTimeOffset AppliedUtc;
        public List<FileRecord> Files = new();

        internal static string Serialize(GamePresetKind kind, DateTimeOffset at, IEnumerable<FileState> touched)
        {
            using var stream = new MemoryStream();
            using (var w = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true }))
            {
                w.WriteStartObject();
                w.WriteNumber("schema", UndoSchema);
                w.WriteString("version", GamePresetOptions.SupportedVersion);
                w.WriteString("kind", kind.ToString());
                w.WriteString("appliedUtc", at.ToString("o", CultureInfo.InvariantCulture));
                w.WriteStartArray("files");
                foreach (var s in touched)
                {
                    w.WriteStartObject();
                    w.WriteString("file", s.File.ToString());
                    w.WriteBoolean("existed", s.Existed);
                    if (s.Existed) w.WriteString("original", s.OriginalText);
                    w.WriteString("writtenSha256", Sha256(s.NewText!, s.File));
                    w.WriteStartArray("keys");
                    foreach (var k in s.Touches)
                    {
                        w.WriteStartObject();
                        w.WriteString("key", k.Key);
                        w.WriteBoolean("existed", k.Existed);
                        if (k.Existed) w.WriteString("old", k.Old);
                        w.WriteEndObject();
                    }
                    w.WriteEndArray();
                    w.WriteEndObject();
                }
                w.WriteEndArray();
                w.WriteEndObject();
            }
            return new UTF8Encoding(false).GetString(stream.ToArray());
        }

        /// <summary>The record, or null (with a reason) when the file is missing, unreadable or not one we wrote.</summary>
        internal static UndoRecord? TryLoad(string path, out string error)
        {
            error = "";
            try
            {
                if (!File.Exists(path)) { error = "There is no undo information."; return null; }
                using var doc = JsonDocument.Parse(File.ReadAllText(path, new UTF8Encoding(false)));
                var root = doc.RootElement;
                if (root.GetProperty("schema").GetInt32() != UndoSchema) { error = "It was written by a different version."; return null; }
                if (root.GetProperty("version").GetString() != GamePresetOptions.SupportedVersion) { error = "It is for another game version."; return null; }
                var record = new UndoRecord
                {
                    Kind = Enum.Parse<GamePresetKind>(root.GetProperty("kind").GetString()!),
                    AppliedUtc = DateTimeOffset.Parse(root.GetProperty("appliedUtc").GetString()!, CultureInfo.InvariantCulture),
                };
                foreach (var f in root.GetProperty("files").EnumerateArray())
                {
                    var file = Enum.Parse<GamePresetFile>(f.GetProperty("file").GetString()!);
                    if (!Enum.IsDefined(file)) throw new FormatException();
                    var existed = f.GetProperty("existed").GetBoolean();
                    var keys = new List<UndoKey>();
                    foreach (var k in f.GetProperty("keys").EnumerateArray())
                    {
                        var kExisted = k.GetProperty("existed").GetBoolean();
                        var key = k.GetProperty("key").GetString();
                        if (string.IsNullOrEmpty(key)) throw new FormatException();
                        keys.Add(new UndoKey(key, kExisted, kExisted ? k.GetProperty("old").GetString() : null));
                    }
                    record.Files.Add(new FileRecord(file, existed, existed ? f.GetProperty("original").GetString() : null,
                        f.GetProperty("writtenSha256").GetString() ?? throw new FormatException(), keys));
                }
                return record;
            }
            catch (Exception ex) when (ex is JsonException or KeyNotFoundException or InvalidOperationException or FormatException
                                           or ArgumentException or IOException or UnauthorizedAccessException)
            {
                error = "Applying a preset again starts a fresh undo.";
                return null;
            }
        }
    }

    // A damaged undo file is set aside (not overwritten blind) before a new Apply writes a fresh one.
    private static void KeepDamagedUndo(string undoPath)
    {
        if (!File.Exists(undoPath) || UndoRecord.TryLoad(undoPath, out _) != null) return;
        try
        {
            File.Move(undoPath, undoPath + ".bad", overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // The fresh record overwrites it atomically; the damaged one held nothing usable.
        }
    }

    // ------------------------------------------------------------------ helpers

    // Puts the files written so far back to their original text. True when every one went back.
    private static bool RollBack(List<FileState> written)
    {
        var ok = true;
        foreach (var s in written)
        {
            try
            {
                if (s.Existed) WriteText(s.Path, s.OriginalText!, s.File);
                else File.Delete(s.Path);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                ok = false;
            }
        }
        return ok;
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // A leftover record is harmless: Undo finds every file untouched and restores nothing new.
        }
    }

    // Latin-1 for the two Minecraft files (see the class comment), UTF-8 for Origin's JSON.
    private static Encoding EncodingOf(GamePresetFile file) => file == GamePresetFile.Origin ? Utf8 : Latin1;

    private static string ReadText(string path, GamePresetFile file) => EncodingOf(file).GetString(File.ReadAllBytes(path));

    private static void WriteText(string path, string text, GamePresetFile file) =>
        AtomicFile.WriteAllBytes(path, EncodingOf(file).GetBytes(text), waitOutReaders: false);

    private static string Sha256(string text, GamePresetFile file) =>
        Convert.ToHexString(SHA256.HashData(EncodingOf(file).GetBytes(text)));

    private static SemaphoreSlim GateFor(string gameDir) =>
        Gates.GetOrAdd(Path.GetFullPath(gameDir).TrimEnd('\\', '/'), _ => new SemaphoreSlim(1, 1));

    // A pending record the files were written for (its written hash matches a file now) is the newest record.
    private static bool WasApplied(GamePresetTarget account, UndoRecord record) =>
        record.Files.Any(f =>
        {
            try
            {
                var path = PathOf(account, f.File);
                return File.Exists(path) && Sha256(ReadText(path, f.File), f.File) == f.WrittenSha256;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return false; }
        });

    // The record Undo and PendingUndo use: a pending one whose files were written (an Apply cut off before it
    // could swap its record in), else the real one.
    private static UndoRecord? LoadCurrent(GamePresetTarget account, out string error)
    {
        var undoPath = InstanceLayout.PresetUndoFile(account.GameDir);
        var pending = UndoRecord.TryLoad(undoPath + PendingSuffix, out _);
        if (pending != null && WasApplied(account, pending)) { error = ""; return pending; }
        return UndoRecord.TryLoad(undoPath, out error);
    }

    // A pending file left by a cut-off Apply: promote it when its files were written, else it is stale: drop it.
    private static void SettlePending(GamePresetTarget account, string undoPath, string pendingPath)
    {
        if (!File.Exists(pendingPath)) return;
        var pending = UndoRecord.TryLoad(pendingPath, out _);
        if (pending != null && WasApplied(account, pending)) Promote(pendingPath, undoPath);
        else TryDelete(pendingPath);
    }

    // pending -> real, replacing the real one in a single rename. Failure leaves the pending file (see above).
    private static void Promote(string pendingPath, string undoPath)
    {
        try
        {
            File.Move(pendingPath, undoPath, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Undo and PendingUndo still find the pending record.
        }
    }
}
