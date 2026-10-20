using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Runtime;
using Xunit;

namespace OriginLauncher.Tests;

// 1.8.9-era launches: the auto heap is capped (4 GB, 6 GB for heavy packs), a heap the player set is
// untouched, the G1 flags follow the heap, and the pinned Temurin 8 is used only when its hash matched.
public class LegacyJavaHeapTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-legacyjava-" + Guid.NewGuid().ToString("N"));

    public LegacyJavaHeapTests()
    {
        Directory.CreateDirectory(_dir);
        JavaRuntimeProvisioner.ResetFailuresForTests();
    }

    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    // --- heap ---

    private const int Pc16Gb = 15872; // what Windows reports for a "16 GB" PC: suggestion = 8 GB
    private const int Pc32Gb = 32000;
    private const int Pc8Gb = 7900;

    [Fact]
    public void Legacy_auto_heap_is_capped_at_4gb_on_a_big_pc()
    {
        var p = HeapPlan.For(manualMb: 12288, manual: false, Pc32Gb, legacy: true, heavyPack: false);
        Assert.Equal(4096, p.MaxMb);
        Assert.Equal(4096, p.StartMb);
        Assert.True(p.Auto);
    }

    [Fact]
    public void Auto_ignores_the_stored_number()
    {
        // Auto never reads RamMb: whatever is stored there, the PC and version decide.
        Assert.Equal(4096, HeapPlan.For(manualMb: 999999, manual: false, Pc32Gb, legacy: true, heavyPack: false).MaxMb);
        Assert.Equal(8192, HeapPlan.For(manualMb: 1024, manual: false, Pc32Gb, legacy: false, heavyPack: false).MaxMb);
    }

    [Fact]
    public void Legacy_auto_on_a_small_pc_follows_the_pc()
    {
        Assert.Equal(3072, HeapPlan.For(0, false, 6144, legacy: true, heavyPack: false).MaxMb); // 6 GB PC -> 3 GB
        Assert.Equal(4096, HeapPlan.For(0, false, Pc8Gb, legacy: true, heavyPack: false).MaxMb);
    }

    [Fact]
    public void Legacy_heavy_pack_allows_6gb_when_the_pc_has_it()
    {
        Assert.Equal(6144, HeapPlan.For(0, false, Pc32Gb, legacy: true, heavyPack: true).MaxMb);
        Assert.Equal(4096, HeapPlan.For(0, false, Pc8Gb, legacy: true, heavyPack: true).MaxMb); // 8 GB PC: half
    }

    [Fact]
    public void Modern_auto_is_half_the_pc_up_to_8gb()
    {
        Assert.Equal(8192, HeapPlan.For(0, false, Pc16Gb, legacy: false, heavyPack: false).MaxMb);
        Assert.Equal(8192, HeapPlan.For(0, false, Pc32Gb, legacy: false, heavyPack: false).MaxMb);
        Assert.Equal(4096, HeapPlan.For(0, false, Pc8Gb, legacy: false, heavyPack: false).MaxMb);
        // heavy-pack only matters for legacy
        Assert.Equal(8192, HeapPlan.For(0, false, Pc32Gb, legacy: false, heavyPack: true).MaxMb);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Manual_heap_is_used_exactly_on_every_version(bool legacy)
    {
        var p = HeapPlan.For(manualMb: 12288, manual: true, Pc32Gb, legacy, heavyPack: false);
        Assert.Equal(12288, p.MaxMb);
        Assert.Equal(12288, p.StartMb);
        Assert.False(p.Auto);
        // legacy only gets a heads-up about a big heap; modern says nothing
        Assert.Equal(legacy, p.Warning != null);
    }

    [Fact]
    public void Manual_4gb_on_legacy_has_no_warning()
    {
        var p = HeapPlan.For(4096, true, Pc32Gb, legacy: true, heavyPack: false);
        Assert.Equal(4096, p.MaxMb);
        Assert.Null(p.Warning);
    }

    // --- Auto / Manual stickiness and migration ---

    private static int Total => Pc16Gb;
    private static int Suggestion => RamAdvice.RecommendedMb(Pc16Gb);

    [Fact]
    public void Fresh_install_is_auto()
    {
        var s = RamPolicy.Fresh(Total);
        Assert.False(RamPolicy.IsManual(s));
        Assert.Equal(Suggestion, s.RamMb);
    }

    [Fact]
    public void Old_settings_holding_the_old_auto_number_migrate_to_auto()
    {
        var s = new LauncherSettings { RamMb = Suggestion, RamManual = null };
        Assert.True(RamPolicy.Migrate(s, Total));
        Assert.False(RamPolicy.IsManual(s));
    }

    [Fact]
    public void Old_settings_holding_any_other_number_migrate_to_manual_and_keep_it()
    {
        var s = new LauncherSettings { RamMb = 6144, RamManual = null };
        Assert.True(RamPolicy.Migrate(s, Total));
        Assert.True(RamPolicy.IsManual(s));
        Assert.Equal(6144, s.RamMb);
    }

    [Fact]
    public void Migration_runs_once_and_never_touches_a_settled_choice()
    {
        var manual = new LauncherSettings { RamMb = Suggestion, RamManual = true };
        Assert.False(RamPolicy.Migrate(manual, Total));
        Assert.True(RamPolicy.IsManual(manual)); // equal to the suggestion, but the player's: stays Manual

        var auto = new LauncherSettings { RamMb = 12345, RamManual = false };
        Assert.False(RamPolicy.Migrate(auto, Total));
        Assert.False(RamPolicy.IsManual(auto)); // a stale number in an Auto file does not make it Manual
    }

    [Fact]
    public void Migrated_choice_survives_a_later_change_of_pc_ram()
    {
        var s = new LauncherSettings { RamMb = Suggestion };
        RamPolicy.Migrate(s, Total);
        Assert.False(RamPolicy.Migrate(s, 65000)); // saved answer: no second look at the new RAM
        Assert.False(RamPolicy.IsManual(s));
    }

    [Fact]
    public void Settings_json_without_the_field_reads_as_unsettled_and_round_trips()
    {
        var old = JsonSerializer.Deserialize<LauncherSettings>("{\"RamMb\": 8192}")!;
        Assert.Null(old.RamManual);
        old.RamManual = true;
        var again = JsonSerializer.Deserialize<LauncherSettings>(
            JsonSerializer.Serialize(old))!;
        Assert.True(again.RamManual);
    }

    [Fact]
    public void Manual_sticks_and_only_Back_to_Auto_clears_it()
    {
        var s = RamPolicy.Fresh(Total);
        RamPolicy.SetManual(s, 5120);
        Assert.True(RamPolicy.IsManual(s));
        Assert.Equal(5120, s.RamMb);
        // launches, migration and sizing on any PC never change it
        Assert.False(RamPolicy.Migrate(s, 65000));
        _ = HeapPlan.For(s, 65000, legacy: true, heavyPack: true);
        Assert.Equal(5120, s.RamMb);
        Assert.True(RamPolicy.IsManual(s));

        RamPolicy.SetAuto(s, Total);
        Assert.False(RamPolicy.IsManual(s));
        Assert.Equal(Suggestion, s.RamMb);
    }

    [Theory]
    [InlineData(0, 0, 0L, false)]
    [InlineData(24, 0, 0L, false)]
    [InlineData(25, 0, 0L, true)]
    [InlineData(3, 1, 0L, true)]
    [InlineData(3, 0, 256L * 1024 * 1024, true)]
    [InlineData(3, 0, 255L * 1024 * 1024, false)]
    public void Heavy_pack_signals(int mods, int shaders, long packBytes, bool heavy) =>
        Assert.Equal(heavy, HeapPlan.IsHeavy(mods, shaders, packBytes));

    [Fact]
    public void PackProbe_counts_the_instance_folders()
    {
        var mods = Path.Combine(_dir, "mods");
        var game = Path.Combine(_dir, "game");
        Directory.CreateDirectory(mods);
        Directory.CreateDirectory(game);
        File.WriteAllText(Path.Combine(mods, "originclient.jar"), "x");
        for (var i = 0; i < 24; i++) File.WriteAllText(Path.Combine(mods, $"m{i}.jar"), "x");
        Assert.False(PackProbe.IsHeavy(mods, game)); // 24 of theirs; Origin's own jar does not count

        File.WriteAllText(Path.Combine(mods, "m24.jar"), "x");
        Assert.True(PackProbe.IsHeavy(mods, game));
        File.Delete(Path.Combine(mods, "m24.jar"));

        Directory.CreateDirectory(Path.Combine(game, "shaderpacks"));
        File.WriteAllText(Path.Combine(game, "shaderpacks", "Pack.zip"), "x");
        Assert.True(PackProbe.IsHeavy(mods, game));
        Assert.False(PackProbe.IsHeavy(Path.Combine(_dir, "nope"), Path.Combine(_dir, "nope2")));
    }

    // --- G1 flags ---

    [Fact]
    public void Capped_legacy_heap_gets_the_small_young_generation_set()
    {
        var flags = JvmArgPresets.FlagsFor(legacy: true, maxHeapMb: 4096);
        Assert.Contains("-XX:G1NewSizePercent=25", flags);
        Assert.Contains("-XX:G1MaxNewSizePercent=35", flags);
        Assert.Contains("-XX:G1HeapRegionSize=4M", flags);
        Assert.DoesNotContain("-XX:G1HeapRegionSize=8M", flags);
        Assert.DoesNotContain("-XX:+AlwaysPreTouch", flags);
        // everything else is still Aikar's
        Assert.Contains("-XX:+UseG1GC", flags);
        Assert.Equal(JvmArgPresets.AikarsFlags.Count, flags.Count);
    }

    [Fact]
    public void Modern_and_big_heaps_keep_Aikars_flags()
    {
        Assert.Same(JvmArgPresets.AikarsFlags, JvmArgPresets.FlagsFor(false, 4096));
        Assert.Same(JvmArgPresets.AikarsFlags, JvmArgPresets.FlagsFor(false, 12288));
        // a legacy heap the player set above 6 GB is not re-tuned either
        Assert.Same(JvmArgPresets.AikarsFlags, JvmArgPresets.FlagsFor(true, 12288));
    }

    // --- Java runtime ---

    private static byte[] MakeJreZip(bool withJavaw = true, bool nested = true)
    {
        using var ms = new MemoryStream();
        using (var z = new ZipArchive(ms, ZipArchiveMode.Create, true))
        {
            var prefix = nested ? "jdk8u504-b01-jre/" : "";
            if (withJavaw)
                using (var w = new StreamWriter(z.CreateEntry(prefix + "bin/javaw.exe").Open())) w.Write("fake");
            using (var w = new StreamWriter(z.CreateEntry(prefix + "release").Open())) w.Write("JAVA_VERSION=\"1.8.0_504\"");
        }
        return ms.ToArray();
    }

    private static JavaRuntimePin PinFor(byte[] zip, string? hashOverride = null) =>
        new("temurin-test", "https://example.invalid/jre.zip",
            hashOverride ?? Convert.ToHexString(SHA256.HashData(zip)).ToLowerInvariant(), zip.Length);

    private static JavaDownload Serve(byte[] bytes, Action? onCall = null) => (url, dest, frac, ct) =>
    {
        onCall?.Invoke();
        File.WriteAllBytes(dest, bytes);
        frac?.Report(1);
        return Task.CompletedTask;
    };

    [Fact]
    public async Task Verified_download_installs_and_returns_javaw()
    {
        var zip = MakeJreZip();
        var path = await JavaRuntimeProvisioner.EnsureAsync(PinFor(zip), _dir, Serve(zip), null, default);
        Assert.Equal(Path.Combine(_dir, "temurin-test", "bin", "javaw.exe"), path);
        Assert.True(File.Exists(path));
        Assert.Empty(Directory.EnumerateDirectories(_dir, ".tmp-*")); // temp is cleaned up
    }

    [Fact]
    public async Task Hash_mismatch_is_rejected_nothing_installed_and_not_retried_this_session()
    {
        var zip = MakeJreZip();
        var calls = 0;
        var pin = PinFor(zip, hashOverride: new string('0', 64));
        Assert.Null(await JavaRuntimeProvisioner.EnsureAsync(pin, _dir, Serve(zip, () => calls++), null, default));
        Assert.False(Directory.Exists(Path.Combine(_dir, "temurin-test")));
        Assert.Empty(Directory.EnumerateDirectories(_dir, ".tmp-*"));

        Assert.Null(await JavaRuntimeProvisioner.EnsureAsync(pin, _dir, Serve(zip, () => calls++), null, default));
        Assert.Equal(1, calls);
    }

    [Fact]
    public async Task Download_failure_falls_back_silently()
    {
        var pin = PinFor(MakeJreZip());
        JavaDownload dead = (_, _, _, _) => throw new HttpRequestException("offline");
        Assert.Null(await JavaRuntimeProvisioner.EnsureAsync(pin, _dir, dead, null, default));
    }

    [Fact]
    public async Task Zip_without_javaw_is_rejected()
    {
        var zip = MakeJreZip(withJavaw: false);
        Assert.Null(await JavaRuntimeProvisioner.EnsureAsync(PinFor(zip), _dir, Serve(zip), null, default));
        Assert.False(Directory.Exists(Path.Combine(_dir, "temurin-test")));
    }

    [Fact]
    public async Task Ready_runtime_is_used_without_any_download()
    {
        var zip = MakeJreZip(nested: false);
        var pin = PinFor(zip);
        await JavaRuntimeProvisioner.EnsureAsync(pin, _dir, Serve(zip), null, default);
        var calls = 0;
        var again = await JavaRuntimeProvisioner.EnsureAsync(pin, _dir, Serve(zip, () => calls++), null, default);
        Assert.NotNull(again);
        Assert.Equal(0, calls);
    }

    [Fact]
    public void Half_installed_folder_without_marker_is_not_trusted()
    {
        var dir = Path.Combine(_dir, "temurin-test", "bin");
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, "javaw.exe"), "partial");
        Assert.Null(JavaRuntimeProvisioner.InstalledJavaw(PinFor(MakeJreZip()), _dir));
    }

    [Fact]
    public async Task Cancel_is_not_swallowed()
    {
        var zip = MakeJreZip();
        using var cts = new CancellationTokenSource();
        JavaDownload cancels = (_, _, _, ct) => { cts.Cancel(); ct.ThrowIfCancellationRequested(); return Task.CompletedTask; };
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => JavaRuntimeProvisioner.EnsureAsync(PinFor(zip), _dir, cancels, null, cts.Token));
    }

    [Fact]
    public void Pin_is_a_well_formed_pinned_temurin_8_jre()
    {
        var p = TemurinJava8.Pin;
        Assert.StartsWith("https://github.com/adoptium/temurin8-binaries/releases/download/", p.Url);
        Assert.EndsWith(".zip", p.Url);
        Assert.Matches("^[0-9a-f]{64}$", p.Sha256);
    }
}
