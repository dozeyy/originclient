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

    [Fact]
    public void A_missing_folder_is_not_an_error()
    {
        Assert.Equal(0, UpdateVerifier.DeleteStaleDownloads(Path.Combine(_dir, "nope"), Prefix, new Version(1, 0, 58)));
    }
}
