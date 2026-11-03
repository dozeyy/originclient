using OriginLauncher.App.Core;
using Xunit;

namespace OriginLauncher.Tests;

// settings.json's store fails closed: a file it can't read is never replaced by a new install's defaults (before
// 2026-11-03 one busy read reset profiles, memory, Java arguments and Settings Sync on the next click). Real files in
// a temp folder; the memory probe is swapped so nothing calls the OS.
public class SettingsStoreTests : IDisposable
{
    private readonly string _dir = Directory.CreateTempSubdirectory("settings-store").FullName;
    private readonly string _path;

    public SettingsStoreTests()
    {
        _path = Path.Combine(_dir, "settings.json");
        SettingsStore.FilePath = _path;
        SettingsStore.TotalMemoryMb = () => 16384;
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch { /* best effort */ }
    }

    private const string Saved = """{ "RamMb": 6144, "RamManual": true, "SyncSettingsAcrossVersions": false, "SelectedVersion": "1.20.1" }""";

    [Fact]
    public void UpdateChangesOnlyWhatTheMutationTouches()
    {
        File.WriteAllText(_path, Saved);
        SettingsStore.Update(s => s.SelectedVersion = "1.21.1");

        var after = SettingsStore.Load();
        Assert.Equal("1.21.1", after.SelectedVersion);
        Assert.Equal(6144, after.RamMb);
        Assert.False(after.SyncSettingsAcrossVersions);
    }

    [Fact]
    public void AFileAnotherProgramHoldsIsNeverOverwrittenWithDefaults()
    {
        // Windows: a sharing lock. Elsewhere .NET emulates FileShare.None with an advisory lock its own opens honour.
        File.WriteAllText(_path, Saved);
        using (new FileStream(_path, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            Assert.Throws<IOException>(() => SettingsStore.Update(s => s.SelectedVersion = "1.21.1"));
        }
        Assert.Equal(Saved, File.ReadAllText(_path));
    }

    [Fact]
    public void ADamagedFileIsKeptAsideBeforeAFreshOneReplacesIt()
    {
        File.WriteAllText(_path, "{ \"RamMb\": 6144, ");
        SettingsStore.Update(s => s.SelectedVersion = "1.21.1");

        Assert.Equal("1.21.1", SettingsStore.Load().SelectedVersion);
        var kept = Assert.Single(Directory.GetFiles(_dir, "settings.json.damaged-*"));
        Assert.Equal("{ \"RamMb\": 6144, ", File.ReadAllText(kept));
    }

    // Load never leaves a handle that stops Update's rename (Windows refuses a rename over an open file), and the two
    // never interleave in-process: many readers and writers at once lose no write and throw nothing.
    [Fact]
    public void ReadsDuringWritesNeitherFailTheWriteNorLoseIt()
    {
        File.WriteAllText(_path, Saved);
        var stop = false;
        var readers = Enumerable.Range(0, 4).Select(_ => Task.Run(() =>
        {
            while (!Volatile.Read(ref stop)) Assert.Equal(6144, SettingsStore.Load().RamMb);
        })).ToArray();

        for (var i = 0; i < 200; i++)
        {
            var n = i;
            SettingsStore.Update(s => s.SelectedVersion = "v" + n);
        }
        Volatile.Write(ref stop, true);
        Task.WaitAll(readers);
        Assert.Equal("v199", SettingsStore.Load().SelectedVersion);
    }

    [Fact]
    public void NoFileReadsAsANewInstallAndTheFirstWriteCreatesIt()
    {
        Assert.False(SettingsStore.Load().RamManual);
        SettingsStore.Update(s => s.SelectedVersion = "1.21.1");
        Assert.Equal("1.21.1", SettingsStore.Load().SelectedVersion);
    }
}
