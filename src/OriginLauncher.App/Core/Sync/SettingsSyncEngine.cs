using System.IO;

namespace OriginLauncher.App.Core.Sync;

// Settings Sync's merge rules — pure file logic, no UI, no launcher state, so
// it is unit-tested on Linux (src/OriginLauncher.Tests/SettingsSyncTests.cs).
// SettingsSync (Core/SettingsSync.cs) decides WHEN these run and on which
// instances; this class decides WHAT happens to every key.
//
// Per key, three-way:
//  * HARVEST an instance: a key whose value differs from that instance's
//    baseline was changed by the player in that version. It goes into the
//    store stamped with the file's last-write time, unless the store already
//    holds a newer change of that key (two versions changed it: newer wins).
//    A key MISSING from a file is never a deletion — older versions simply
//    don't have it, or their loader dropped it — except in the Origin configs
//    whose loaders never drop keys (ISyncFormat.AllowsDeletions).
//  * PUSH into the instance being launched: every stored key is written into
//    its files (each format decides how; everything else in the file is kept),
//    and that instance's baseline becomes what is now on disk.
//  * SEED (the switch was turned on): harvest every instance as if nothing had
//    been synced yet — per key the newest file wins, so nothing is lost. An
//    instance whose game is running is seeded when its game exits (SeedPending).
//
// A file Settings Sync has never written to or read from in that instance (a
// version the player hadn't installed when the switch went on, a config the
// game recreated after it was deleted or corrupted) — and likewise a key that
// first appears in a file after we last synced it — only FILLS GAPS: fresh
// defaults must not beat settings the player chose elsewhere just because
// their file is newer.
public sealed class SettingsSyncEngine
{
    // When this many of a file's keys vanish at once it is a reset or a
    // damaged load in the game, not the player deleting things one by one —
    // record no deletions for it (the keys simply come back on the next push).
    private const int MassDeletionFloor = 8;
    private const double MassDeletionShare = 0.25;

    // A stored change stamped further than this ahead of the clock was made while the PC's clock ran ahead.
    // The slack covers file systems that round write times up (FAT: 2 s) and small clock corrections.
    internal const long FutureSlackMs = 5 * 60 * 1000;

    private readonly Action<string> _log;
    private readonly Func<long> _nowMs;

    /// <param name="nowMs">The clock, Unix milliseconds UTC; tests pass their own.</param>
    public SettingsSyncEngine(SyncStoreData store, Action<string>? log = null, Func<long>? nowMs = null)
    {
        Store = store;
        _log = log ?? (_ => { });
        _nowMs = nowMs ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    }

    public SyncStoreData Store { get; }

    /// <summary>
    /// Rebuilds the store from scratch: per key the newest file among all instances wins. <paramref name="running"/>
    /// are the versions left out because their game is running: each is seeded when it is next harvested (its game
    /// exited), so turning the switch on mid-session loses nothing from that session.
    /// </summary>
    public void Seed(IReadOnlyList<SyncInstance> instances, IEnumerable<string>? running = null)
    {
        Store.Files.Clear();
        Store.Baselines.Clear();
        Store.SeedPending = (running ?? Enumerable.Empty<string>()).Distinct(StringComparer.Ordinal).ToList();
        var files = SyncedFiles.All(instances, Store);
        foreach (var instance in instances)
            foreach (var file in files)
                Guard(instance, file, "seed", () => HarvestFile(instance, file, seeding: true));
    }

    /// <summary>Takes the player's changes in one instance into the store.</summary>
    public void Harvest(SyncInstance instance)
    {
        // Left out of the seed while its game ran: this first read is that instance's seed (newest file wins per key).
        var seeding = Store.SeedPending.Remove(instance.Version);
        if (seeding) _log($"{instance.Version}: seeded now (its game was running when Settings Sync was turned on)");
        foreach (var file in SyncedFiles.All(new[] { instance }, Store))
            Guard(instance, file, "harvest", () => HarvestFile(instance, file, seeding));
    }

    /// <summary>Writes the store into one instance (call Harvest on every instance first).</summary>
    public void Push(SyncInstance instance)
    {
        foreach (var file in SyncedFiles.All(new[] { instance }, Store))
            Guard(instance, file, "push", () => PushFile(instance, file));
    }

    private void HarvestFile(SyncInstance instance, SyncedFile file, bool seeding)
    {
        var path = file.PathIn(instance.Directory);
        var format = file.Format;
        var current = File.Exists(path) ? format.Read(path) : null;
        if (current == null)
        {
            // Missing or unreadable. Whatever the game writes here next is a
            // fresh default file (the launcher deletes NUL-corrupted configs,
            // mods reset unreadable ones), so it must count as never synced —
            // not as the player changing every value.
            ForgetBaseline(instance, file);
            if (File.Exists(path)) _log($"{instance.Version}: skipped unreadable {file.Id}");
            return;
        }

        // Time stamps are only as good as the PC's clock. A file written while the clock ran ahead (a CMOS reset, a
        // dual-boot clock hours off, a manual date change) carries a future time, and so did every change harvested
        // from it. Before 2026-11-04 such a stored change was "newer" than anything the player did after the clock
        // was fixed, so their new value was skipped here and the old one pushed back on every launch until real
        // time caught up. Now nothing is stamped later than now, and a stored stamp from the future loses to any
        // real change (Stamp).
        var now = _nowMs();
        var changedAt = Math.Min(AtomicFile.LastWriteMs(path), now);
        var baseline = seeding ? new Dictionary<string, string>() : Store.BaselineOf(instance.Version, file.Id);
        var values = Store.FileValues(file.Id);

        foreach (var (key, raw) in current)
        {
            if (format.Rejects(key, raw) || format.StaysIn(key, instance.Version)) continue;
            if (baseline != null && baseline.TryGetValue(key, out var before) && format.MatchesBaseline(key, raw, before))
                continue; // not changed in this version since we last synced it

            if (values.TryGetValue(key, out var stored))
            {
                if (stored.Value != null && format.Equivalent(key, stored.Value, raw)) continue;
                // Never synced here (no baseline, or a key this instance's file
                // didn't have when we last wrote it — e.g. Iris filling in its
                // own keys on first run): that is the version's default, so it
                // only fills gaps and never beats a value the store has.
                if (!seeding && (baseline == null || !baseline.ContainsKey(key))) continue;
                if (Stamp(stored, now) >= changedAt) continue; // the store's change is newer
            }
            values[key] = new SyncValue(raw, changedAt);
        }

        if (format.AllowsDeletions && baseline != null && !seeding)
            HarvestDeletions(instance, file, baseline, current, values, changedAt, now);

        Store.SetBaseline(instance.Version, file.Id, BaselineFor(format, current));
    }

    private void HarvestDeletions(SyncInstance instance, SyncedFile file, Dictionary<string, string> baseline,
        Dictionary<string, string> current, Dictionary<string, SyncValue> values, long changedAt, long now)
    {
        var vanished = baseline.Keys.Where(k => !current.ContainsKey(k)).ToList();
        if (vanished.Count == 0) return;
        if (vanished.Count > Math.Max(MassDeletionFloor, baseline.Count * MassDeletionShare))
        {
            _log($"{instance.Version}: {vanished.Count} keys vanished from {file.Id} at once - treated as a reset, not deletions");
            return;
        }
        foreach (var key in vanished)
        {
            if (values.TryGetValue(key, out var stored) && Stamp(stored, now) >= changedAt) continue;
            values[key] = new SyncValue(null, changedAt);
        }
    }

    private void PushFile(SyncInstance instance, SyncedFile file)
    {
        if (!Store.Files.TryGetValue(file.Id, out var values) || values.Count == 0) return;
        var path = file.PathIn(instance.Directory);
        var desired = values.ToDictionary(kv => kv.Key, kv => kv.Value.Value, StringComparer.Ordinal);
        var result = file.Format.Apply(path, desired, instance);
        if (result != null && File.Exists(path))
            Store.SetBaseline(instance.Version, file.Id, BaselineFor(file.Format, result));
        else if (!File.Exists(path) || file.Format.Read(path) == null)
            // No file (skipped, or nothing to create) or an unreadable one: the
            // file the game writes next must be read as "never synced".
            ForgetBaseline(instance, file);
    }

    // A stored change's time for "which is newer": its own, unless the clock was ahead when it was made; then it
    // counts as older than any real change (long.MinValue), so the player's change made today wins.
    private static long Stamp(SyncValue stored, long now) =>
        stored.ChangedAt > now + FutureSlackMs ? long.MinValue : stored.ChangedAt;

    private void ForgetBaseline(SyncInstance instance, SyncedFile file)
    {
        if (Store.Baselines.TryGetValue(instance.Version, out var files))
            files.Remove(file.Id);
    }

    private static Dictionary<string, string> BaselineFor(ISyncFormat format, Dictionary<string, string> values) =>
        values.ToDictionary(kv => kv.Key, kv => format.BaselineOf(kv.Value), StringComparer.Ordinal);

    // Mandate 4: one bad file never stops the rest, and never reaches the launch.
    private void Guard(SyncInstance instance, SyncedFile file, string step, Action action)
    {
        try
        {
            action();
        }
        catch (Exception ex)
        {
            _log($"{instance.Version}: {step} of {file.Id} failed: {ex.GetType().Name}: {ex.Message}");
        }
    }
}
