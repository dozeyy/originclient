using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// The small self-update (Core/Updates/UpdateDelta.cs): the new release rebuilt from the installed folder plus only
// the changed files, every staged file checked against the manifest, and ANY problem answering "use the full zip"
// with an empty stage and the installed folder untouched. Real files in temp folders.
//
// Fixtures/Delta/delta-from-1.0.1-to-1.0.2.zip is made by the real release builder, so this also proves the two
// sides agree on the format:
//   python3 tools/release/delta.py build old.zip new.zip delta-from-1.0.1-to-1.0.2.zip --from 1.0.1 --to 1.0.2
// with old.zip = OldRelease and new.zip = NewRelease below.
public sealed class UpdateDeltaTests : IDisposable
{
    private static readonly Dictionary<string, string> OldRelease = new()
    {
        ["OriginLauncher.App.exe"] = "exe v1",
        ["Bundled/OriginClient/originclient-1.21.1.jar"] = "jar unchanged",
        ["gone.dll"] = "removed in 1.0.2",
    };

    private static readonly Dictionary<string, string> NewRelease = new()
    {
        ["OriginLauncher.App.exe"] = "exe v2",
        ["Bundled/OriginClient/originclient-1.21.1.jar"] = "jar unchanged",
        ["Bundled/OriginClient/originclient-26.3.jar"] = "added in 1.0.2",
    };

    private static readonly Version V1 = new(1, 0, 1, 0); // assembly versions carry the 4th part
    private static readonly Version V2 = new(1, 0, 2, 0);

    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-delta-" + Guid.NewGuid().ToString("N"));
    private readonly string _installed;
    private readonly string _stage;

    public UpdateDeltaTests()
    {
        _installed = Path.Combine(_root, "installed");
        _stage = Path.Combine(_root, "updates", "1.0.2");
        WriteTree(_installed, OldRelease);
        Directory.CreateDirectory(_stage);
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private static string Fixture => Path.Combine(AppContext.BaseDirectory, "Fixtures", "Delta", "delta-from-1.0.1-to-1.0.2.zip");

    // ---- the happy path ----

    [Fact]
    public void A_delta_built_by_the_release_script_rebuilds_exactly_the_new_release()
    {
        var installedBefore = ReadTree(_installed);

        Assert.True(UpdateDelta.TryStage(Fixture, _installed, _stage, V1, V2, out var failure), failure);

        // Exactly the new release: the changed exe and the added jar from the delta, the unchanged jar from the
        // installed folder, and nothing the new release dropped (the swap never deletes, like the full zip's).
        Assert.Equal(NewRelease, ReadTree(_stage));
        Assert.Equal(installedBefore, ReadTree(_installed));
    }

    // ---- every failure: false, an empty stage, the installed folder untouched ----

    [Fact]
    public void An_installed_file_that_changed_locally_falls_back_to_the_full_zip()
    {
        File.WriteAllText(Path.Combine(_installed, "Bundled", "OriginClient", "originclient-1.21.1.jar"), "edited by antivirus");
        AssertFallsBack(Fixture);
    }

    [Fact]
    public void An_installed_file_that_is_missing_falls_back_to_the_full_zip()
    {
        File.Delete(Path.Combine(_installed, "Bundled", "OriginClient", "originclient-1.21.1.jar"));
        AssertFallsBack(Fixture);
    }

    [Fact]
    public void A_delta_made_for_another_installed_version_is_not_used()
    {
        AssertFallsBack(Fixture, from: new Version(1, 0, 0, 0));
    }

    [Fact]
    public void A_delta_attached_to_the_wrong_release_is_not_used()
    {
        // Would otherwise swap in 1.0.2 while the launcher believes it installed 1.0.3.
        AssertFallsBack(Fixture, to: new Version(1, 0, 3, 0));
    }

    [Fact]
    public void A_file_that_is_not_a_zip_falls_back()
    {
        var bad = Path.Combine(_root, "bad.zip");
        File.WriteAllText(bad, "not a zip at all");
        AssertFallsBack(bad);
    }

    [Fact]
    public void A_changed_file_in_the_delta_that_does_not_match_the_manifest_falls_back()
    {
        var files = new Dictionary<string, string>(NewRelease);
        var delta = MakeDelta(Manifest("1.0.1", "1.0.2", files),
            ("files/OriginLauncher.App.exe", "exe v2 but damaged"), ("files/Bundled/OriginClient/originclient-26.3.jar", "added in 1.0.2"));
        AssertFallsBack(delta);
    }

    [Fact]
    public void A_delta_carrying_a_file_its_manifest_does_not_list_is_not_used()
    {
        var delta = MakeDelta(Manifest("1.0.1", "1.0.2", NewRelease),
            ("files/OriginLauncher.App.exe", "exe v2"), ("files/Bundled/OriginClient/originclient-26.3.jar", "added in 1.0.2"),
            ("files/extra.dll", "not in the manifest"));
        AssertFallsBack(delta);
    }

    [Fact]
    public void A_delta_with_an_entry_outside_files_is_not_used()
    {
        var delta = MakeDelta(Manifest("1.0.1", "1.0.2", NewRelease),
            ("files/OriginLauncher.App.exe", "exe v2"), ("files/Bundled/OriginClient/originclient-26.3.jar", "added in 1.0.2"),
            ("stray.txt", "x"));
        AssertFallsBack(delta);
    }

    [Theory]
    [InlineData("../escape.dll")]
    [InlineData("Bundled/../../escape.dll")]
    [InlineData("/rooted.dll")]
    [InlineData("C:/Windows/evil.dll")]
    [InlineData("back\\slash.dll")]
    [InlineData("OriginLauncher.App.exe:stream")]
    public void A_manifest_path_that_could_leave_the_stage_is_refused_and_nothing_is_written_outside(string path)
    {
        var files = new Dictionary<string, string>(NewRelease) { [path] = "evil" };
        var delta = MakeDelta(Manifest("1.0.1", "1.0.2", files),
            ("files/OriginLauncher.App.exe", "exe v2"), ("files/Bundled/OriginClient/originclient-26.3.jar", "added in 1.0.2"),
            ("files/" + path, "evil"));
        AssertFallsBack(delta);
        Assert.False(File.Exists(Path.Combine(_root, "escape.dll")));
        Assert.False(File.Exists(Path.Combine(_root, "updates", "escape.dll")));
    }

    [Fact]
    public void A_manifest_listing_the_same_file_twice_in_another_case_is_refused()
    {
        // One file on Windows; the default (ordinal) dictionary keeps both keys.
        var files = new Dictionary<string, string>(NewRelease) { ["originlauncher.app.exe"] = "exe v2" };
        var delta = MakeDelta(Manifest("1.0.1", "1.0.2", files),
            ("files/OriginLauncher.App.exe", "exe v2"), ("files/Bundled/OriginClient/originclient-26.3.jar", "added in 1.0.2"));
        AssertFallsBack(delta);
    }

    [Fact]
    public void A_stage_that_is_not_empty_is_never_touched()
    {
        var other = Path.Combine(_stage, "someone-elses.txt");
        File.WriteAllText(other, "keep me");

        Assert.False(UpdateDelta.TryStage(Fixture, _installed, _stage, V1, V2, out _));

        Assert.Equal("keep me", File.ReadAllText(other));
    }

    // The fallback composes: after a failed delta the stage is empty, and unpacking the full zip into it (what
    // UpdateService does next) gives exactly the new release, with no leftover from the delta's attempt.
    [Fact]
    public void After_a_failed_delta_the_full_zip_stages_exactly_the_new_release()
    {
        var delta = MakeDelta(Manifest("1.0.1", "1.0.2", NewRelease),
            ("files/OriginLauncher.App.exe", "exe v2"), ("files/Bundled/OriginClient/originclient-26.3.jar", "added in 1.0.2 DAMAGED"));
        Assert.False(UpdateDelta.TryStage(delta, _installed, _stage, V1, V2, out _));

        var fullZip = Path.Combine(_root, "full.zip");
        using (var zip = ZipFile.Open(fullZip, ZipArchiveMode.Create))
            foreach (var (path, text) in NewRelease)
                using (var w = new StreamWriter(zip.CreateEntry(path).Open())) w.Write(text);
        ZipFile.ExtractToDirectory(fullZip, _stage, overwriteFiles: true);

        Assert.Equal(NewRelease, ReadTree(_stage));
    }

    // ---- the check before the swap, on its own ----

    [Fact]
    public void The_check_before_the_swap_catches_a_staged_file_changed_after_it_was_written()
    {
        Assert.True(UpdateDelta.TryStage(Fixture, _installed, _stage, V1, V2, out var failure), failure);
        var files = ManifestOf(Fixture);
        UpdateDelta.VerifyStaged(_stage, files); // clean stage passes

        File.WriteAllText(Path.Combine(_stage, "OriginLauncher.App.exe"), "exe v3"); // same length, other bytes
        Assert.Throws<UpdateDeltaException>(() => UpdateDelta.VerifyStaged(_stage, files));
    }

    [Fact]
    public void The_check_before_the_swap_catches_a_short_or_extra_file()
    {
        Assert.True(UpdateDelta.TryStage(Fixture, _installed, _stage, V1, V2, out var failure), failure);
        var files = ManifestOf(Fixture);

        File.WriteAllText(Path.Combine(_stage, "stray.dll"), "x");
        Assert.Throws<UpdateDeltaException>(() => UpdateDelta.VerifyStaged(_stage, files));
        File.Delete(Path.Combine(_stage, "stray.dll"));

        File.WriteAllText(Path.Combine(_stage, "OriginLauncher.App.exe"), "exe"); // partial copy
        Assert.Throws<UpdateDeltaException>(() => UpdateDelta.VerifyStaged(_stage, files));
    }

    // ---- which release asset is this install's delta ----

    [Theory]
    [InlineData("OriginLauncher-win-x64.delta-from-1.0.84.zip", true)]
    [InlineData("originlauncher-win-x64.DELTA-FROM-1.0.84.ZIP", true)]
    [InlineData("OriginLauncher-win-x64.delta-from-1.0.83.zip", false)]
    [InlineData("OriginLauncher-win-x64.delta-from-1.0.84.zip.sha256", false)]
    [InlineData("OriginLauncher-win-x64.zip", false)]
    [InlineData("OriginLauncher-osx-arm64.delta-from-1.0.84.zip", false)]
    [InlineData("OriginLauncher-win-x64.delta-from-banana.zip", false)]
    [InlineData(null, false)]
    public void Only_the_delta_from_exactly_the_installed_version_matches(string? asset, bool matches)
    {
        Assert.Equal(matches, UpdateDelta.IsDeltaFor(asset, "OriginLauncher-win-x64", new Version(1, 0, 84, 0)));
    }

    [Theory]
    [InlineData("OriginLauncher.App.exe", true)]
    [InlineData("Bundled/OriginClient/originclient-1.21.1.jar", true)]
    [InlineData("", false)]
    [InlineData("a//b", false)]
    [InlineData("a/./b", false)]
    [InlineData("a/", false)]
    public void Safe_paths_are_relative_and_plain(string path, bool safe)
    {
        Assert.Equal(safe, UpdateDelta.IsSafeRelativePath(path));
    }

    // ---- helpers ----

    private void AssertFallsBack(string delta, Version? from = null, Version? to = null)
    {
        var installedBefore = ReadTree(_installed);

        Assert.False(UpdateDelta.TryStage(delta, _installed, _stage, from ?? V1, to ?? V2, out var failure));

        Assert.False(string.IsNullOrEmpty(failure));
        Assert.True(Directory.Exists(_stage));
        Assert.Empty(Directory.EnumerateFileSystemEntries(_stage));
        Assert.Equal(installedBefore, ReadTree(_installed));
    }

    private static IReadOnlyList<UpdateDelta.ManifestFile> ManifestOf(string delta)
    {
        using var zip = ZipFile.OpenRead(delta);
        return UpdateDelta.ReadManifest(zip, V1, V2);
    }

    private static string Sha(string text) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text))).ToLowerInvariant();

    private static string Manifest(string from, string to, Dictionary<string, string> files) =>
        JsonSerializer.Serialize(new
        {
            format = 1,
            from,
            to,
            files = files.Select(f => new { path = f.Key, size = Encoding.UTF8.GetByteCount(f.Value), sha256 = Sha(f.Value) }),
            deleted = Array.Empty<string>(),
        });

    private string MakeDelta(string manifestJson, params (string Entry, string Text)[] entries)
    {
        var path = Path.Combine(_root, "delta-" + Guid.NewGuid().ToString("N") + ".zip");
        using var zip = ZipFile.Open(path, ZipArchiveMode.Create);
        using (var w = new StreamWriter(zip.CreateEntry(UpdateDelta.ManifestEntry).Open())) w.Write(manifestJson);
        foreach (var (entry, text) in entries)
            using (var w = new StreamWriter(zip.CreateEntry(entry).Open())) w.Write(text);
        return path;
    }

    private static void WriteTree(string root, Dictionary<string, string> files)
    {
        foreach (var (path, text) in files)
        {
            var full = Path.Combine(root, path.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(full)!);
            File.WriteAllText(full, text);
        }
    }

    private static Dictionary<string, string> ReadTree(string root) =>
        Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories).ToDictionary(
            f => Path.GetRelativePath(root, f).Replace(Path.DirectorySeparatorChar, '/'),
            File.ReadAllText);
}
