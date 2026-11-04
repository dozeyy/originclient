using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// Stale self-update downloads (~126 MB each) must not pile up when the player quits before applying or the
// process is killed mid-download (UpdateVerifier.DeleteStaleDownloads). Real files in a temp folder.
public sealed class UpdateCleanupTests : IDisposable
{
    private const string Prefix = "OriginLauncher-win-x64";
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-update-clean-" + Guid.NewGuid().ToString("N"));

    public UpdateCleanupTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    private string Touch(string name)
    {
        var p = Path.Combine(_dir, name);
        File.WriteAllText(p, "x");
        return p;
    }

    [Fact]
    public void Removes_other_versions_zips_and_parts_but_keeps_the_fetched_one()
    {
        var oldZip = Touch($"{Prefix}-1.0.56.zip");
        var oldPart = Touch($"{Prefix}-1.0.57.zip.part");
        var keepZip = Touch($"{Prefix}-1.0.58.zip");
        var keepPart = Touch($"{Prefix}-1.0.58.zip.part");

        var n = UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, new Version(1, 0, 58));

        Assert.Equal(2, n);
        Assert.False(File.Exists(oldZip));
        Assert.False(File.Exists(oldPart));
        Assert.True(File.Exists(keepZip));
        Assert.True(File.Exists(keepPart));
    }

    // A quit during an older launcher's download left updates/OriginLauncher-win-x64.zip (the old fixed name): the
    // per-version pattern never matched it, so ~126 MB stayed forever.
    [Fact]
    public void The_old_fixed_name_zip_and_its_part_are_removed_even_when_versions_are_kept()
    {
        var legacyZip = Touch($"{Prefix}.zip");
        var legacyPart = Touch($"{Prefix}.zip.part");
        var keep = Touch($"{Prefix}-1.0.58.zip");
        var other = Touch($"{Prefix}-extra.txt");

        var n = UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, new Version(1, 0, 58));

        Assert.Equal(2, n);
        Assert.False(File.Exists(legacyZip));
        Assert.False(File.Exists(legacyPart));
        Assert.True(File.Exists(keep));
        Assert.True(File.Exists(other));
    }

    [Fact]
    public void The_cleanup_folder_is_the_one_OriginPaths_names()
    {
        // UpdateService hands OriginPaths.Updates to the cleanup: it must sit under the data root.
        Assert.Equal(Path.Combine(OriginPaths.Root, "updates"), OriginPaths.Updates);
    }

    [Fact]
    public void Keeps_every_listed_version_and_ignores_null_entries()
    {
        var a = Touch($"{Prefix}-1.0.58.zip");
        var b = Touch($"{Prefix}-1.0.59.zip");
        var gone = Touch($"{Prefix}-1.0.50.zip");

        UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, new Version(1, 0, 58), null, new Version(1, 0, 59));

        Assert.True(File.Exists(a));
        Assert.True(File.Exists(b));
        Assert.False(File.Exists(gone));
    }

    [Fact]
    public void Startup_with_nothing_to_keep_removes_all_download_files()
    {
        Touch($"{Prefix}-1.0.56.zip");
        Touch($"{Prefix}-1.0.57.zip.part");

        Assert.Equal(2, UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, (Version?)null));
        Assert.Empty(Directory.GetFiles(_dir));
    }

    [Fact]
    public void Leaves_unrelated_files_and_stage_folders_alone()
    {
        var other = Touch("notes.txt");
        var odd = Touch($"{Prefix}-1.0.56.zip.bak");
        var stage = Path.Combine(_dir, "1.0.56");
        Directory.CreateDirectory(stage);

        var n = UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, new Version(1, 0, 58));

        Assert.Equal(0, n);
        Assert.True(File.Exists(other));
        Assert.True(File.Exists(odd));
        Assert.True(Directory.Exists(stage));
    }

    [Fact]
    public void A_locked_file_is_skipped_and_the_rest_still_go()
    {
        // An open handle blocks File.Delete only on Windows (the launcher's platform); Linux CI unlinks it fine.
        if (!OperatingSystem.IsWindows()) return;
        var locked = Touch($"{Prefix}-1.0.55.zip.part");
        var free = Touch($"{Prefix}-1.0.56.zip");
        using var hold = new FileStream(locked, FileMode.Open, FileAccess.Read, FileShare.None);

        var n = UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, new Version(1, 0, 58));

        Assert.Equal(1, n);
        Assert.True(File.Exists(locked));
        Assert.False(File.Exists(free));
    }

    // The small update (UpdateService.DeltaPathFor: <prefix>-<version>.delta.zip) of the version being fetched stays;
    // a delta left behind by a kill mid-update, for any other version, goes like its zip would.
    [Fact]
    public void Small_update_downloads_of_other_versions_are_removed_and_the_fetched_one_kept()
    {
        var keep = Touch($"{Prefix}-1.0.58.delta.zip");
        var keepPart = Touch($"{Prefix}-1.0.58.delta.zip.part");
        var stale = Touch($"{Prefix}-1.0.57.delta.zip");
        var stalePart = Touch($"{Prefix}-1.0.57.delta.zip.part");

        var n = UpdateVerifier.DeleteStaleDownloads(_dir, Prefix, new Version(1, 0, 58));

        Assert.Equal(2, n);
        Assert.True(File.Exists(keep));
        Assert.True(File.Exists(keepPart));
        Assert.False(File.Exists(stale));
        Assert.False(File.Exists(stalePart));
    }

    [Fact]
    public void A_missing_folder_is_not_an_error()
    {
        Assert.Equal(0, UpdateVerifier.DeleteStaleDownloads(Path.Combine(_dir, "nope"), Prefix, new Version(1, 0, 58)));
    }

    // Stage folders (updates/<version>, ~250 MB each): a background download's stage superseded by a newer release, or
    // the stage the last swap already copied from, goes; the kept versions' stages and anything not named like a
    // version stay (UpdateVerifier.DeleteStaleStages).
    [Fact]
    public void Stale_stage_folders_go_and_kept_or_foreign_folders_stay()
    {
        string Stage(string name)
        {
            var dir = Path.Combine(_dir, name);
            Directory.CreateDirectory(Path.Combine(dir, "sub"));
            File.WriteAllText(Path.Combine(dir, "sub", "a.dll"), "x");
            return dir;
        }
        var old = Stage("1.0.56");
        var swapped = Stage("1.0.57");
        var kept = Stage("1.0.58");
        var foreign = Stage("logs");
        var almost = Stage("1.0.58.bak");
        var zip = Touch($"{Prefix}-1.0.58.zip");

        var n = UpdateVerifier.DeleteStaleStages(_dir, new Version(1, 0, 58, 0), null);

        Assert.Equal(2, n);
        Assert.False(Directory.Exists(old));
        Assert.False(Directory.Exists(swapped));
        Assert.True(Directory.Exists(kept)); // 1.0.58.0 keeps 1.0.58
        Assert.True(Directory.Exists(foreign));
        Assert.True(Directory.Exists(almost));
        Assert.True(File.Exists(zip)); // files are DeleteStaleDownloads' job
        Assert.Equal(0, UpdateVerifier.DeleteStaleStages(Path.Combine(_dir, "nope")));
    }

    // The background download's stage is re-hashed right before the swap (StageSnapshot): unchanged = swap; a file
    // removed, changed or added since it was staged = the click downloads it again.
    [Fact]
    public void A_stage_matches_its_snapshot_until_anything_in_it_changes()
    {
        var stage = Path.Combine(_dir, "1.0.58");
        Directory.CreateDirectory(Path.Combine(stage, "runtimes"));
        File.WriteAllText(Path.Combine(stage, "OriginLauncher.App.exe"), "exe");
        File.WriteAllText(Path.Combine(stage, "runtimes", "native.dll"), "dll");
        var snapshot = StageSnapshot.Take(stage);
        Assert.Equal(2, snapshot.Count);
        Assert.Contains(snapshot, e => e.Path == "runtimes/native.dll");
        Assert.True(StageSnapshot.Matches(stage, snapshot));

        File.WriteAllText(Path.Combine(stage, "runtimes", "native.dll"), "DLL"); // same size, other bytes
        Assert.False(StageSnapshot.Matches(stage, snapshot));
        File.WriteAllText(Path.Combine(stage, "runtimes", "native.dll"), "dll");
        Assert.True(StageSnapshot.Matches(stage, snapshot));

        File.WriteAllText(Path.Combine(stage, "extra.txt"), "x");
        Assert.False(StageSnapshot.Matches(stage, snapshot));
        File.Delete(Path.Combine(stage, "extra.txt"));

        File.Delete(Path.Combine(stage, "OriginLauncher.App.exe"));
        Assert.False(StageSnapshot.Matches(stage, snapshot));
        Assert.False(StageSnapshot.Matches(Path.Combine(_dir, "gone"), snapshot));
    }

    [Fact]
    public void An_empty_stage_is_never_recorded_as_ready()
    {
        var stage = Path.Combine(_dir, "1.0.58");
        Directory.CreateDirectory(stage);
        Assert.Throws<IOException>(() => StageSnapshot.Take(stage));
    }
}
