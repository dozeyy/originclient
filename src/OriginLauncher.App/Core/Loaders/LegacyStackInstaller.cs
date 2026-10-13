using System.IO;
using System.Net.Http;

namespace OriginLauncher.App.Core.Loaders;

// The legacy (Forge) counterpart of PerformanceModCatalog + PerfModInstaller,
// kept as its own catalog on purpose: the Fabric catalog's named slots
// (Sodium/Iris/...) are gating signals elsewhere (HasShaderStack,
// IsBundledPerfJar purge) and must not learn Forge shapes. Legacy the perf
// story is OptiFine (renderer+shaders, installed by OptiFineInstaller) plus
// the era's best Forge perf mods below — the strongest stack that exists for
// each version:
//   1.12.2: FoamFix (memory), Phosphor (lighting engine), VanillaFix
//           (threading/crash guards), TexFix (texture memory), BetterFps
//           (math + algorithm swaps)
//   1.8.9:  TexFix + BetterFps (the 1.8.9 era has no deeper perf mods —
//           OptiFine IS the perf layer there, same call Lunar made)
// All pins are exact CurseForge CDN files, verified reachable.
public static class LegacyStackInstaller
{
    private static readonly HttpClient Http = new();

    private sealed record LegacyMod(string FileName, string Url);

    private static readonly IReadOnlyDictionary<string, LegacyMod[]> Stacks =
        new Dictionary<string, LegacyMod[]>
        {
            ["1.8.9"] = new[]
            {
                new LegacyMod("TexFix-1.8.9-4.0.jar", "https://edge.forgecdn.net/files/2540/156/TexFix%20V-1.8.9-4.0.jar"),
                new LegacyMod("BetterFps-1.2.0.jar", "https://edge.forgecdn.net/files/2271/480/BetterFps-1.2.0.jar"),
            },
            ["1.12.2"] = new[]
            {
                new LegacyMod("foamfix-0.10.15-1.12.2.jar", "https://edge.forgecdn.net/files/3973/967/foamfix-0.10.15-1.12.2.jar"),
                new LegacyMod("phosphor-forge-mc1.12.2-0.2.7-universal.jar", "https://edge.forgecdn.net/files/2919/497/phosphor-forge-mc1.12.2-0.2.7-universal.jar"),
                new LegacyMod("VanillaFix-1.0.10-150.jar", "https://edge.forgecdn.net/files/2915/154/VanillaFix-1.0.10-150.jar"),
                new LegacyMod("TexFix-1.12-4.0.jar", "https://edge.forgecdn.net/files/2518/68/TexFix%20V-1.12-4.0.jar"),
                new LegacyMod("BetterFps-1.4.8.jar", "https://edge.forgecdn.net/files/2483/393/BetterFps-1.4.8.jar"),
            },
        };

    public static bool Supports(string version) => Stacks.ContainsKey(version);

    /// <summary>
    /// True for a jar this catalog installs (exact pinned file names — the
    /// pins have never changed, so no drifted leftovers exist). ModManager
    /// counts these as Origin-provisioned, so the Mods page shows them
    /// read-only and an Origin-only launch keeps them.
    /// </summary>
    public static bool IsStackJar(string jarName) =>
        Stacks.Values.Any(mods => mods.Any(m => m.FileName.Equals(jarName, StringComparison.OrdinalIgnoreCase)));

    // Into the version's SHARED mods folder (SharedMods): new names only, each
    // downloaded beside and renamed in, so a running game is never disturbed.
    public static async Task InstallAsync(
        string version, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default)
    {
        if (!Stacks.TryGetValue(version, out var mods)) return;
        Directory.CreateDirectory(modsFolder);

        foreach (var mod in mods)
        {
            // Same either-state skip as PerfModInstaller: an enabled ".jar" or
            // a player's ".jar.disabled" both mean "leave it alone".
            var destPath = Path.Combine(modsFolder, mod.FileName);
            if (File.Exists(destPath) || File.Exists(destPath + ".disabled")) continue;

            progress?.Report($"Downloading {mod.FileName}...");
            try
            {
                using var response = await Http.GetAsync(mod.Url, HttpCompletionOption.ResponseHeadersRead, ct);
                response.EnsureSuccessStatusCode();
                var tempPath = destPath + ".download";
                await using (var fileStream = File.Create(tempPath))
                    await response.Content.CopyToAsync(fileStream, ct);
                File.Move(tempPath, destPath, overwrite: true);
            }
            catch (OperationCanceledException) { throw; }
            catch
            {
                // A perf mod is an enhancement, not a requirement — the game
                // must still launch without it. Next launch retries.
            }
        }
    }

    // FML's boot splash (the earliest thing a player sees) is configurable via
    // config/splash.properties — seed it themed to the Origin palette so even
    // the Forge load screen is charcoal + white instead of the default red.
    // Only written when absent: the file is player-ownable config. FML reads
    // it from <gameDir>/config, i.e. the ACCOUNT's own game folder, so every
    // account gets its own copy the first time it plays.
    public static void SeedSplashTheme(string configFolder)
    {
        try
        {
            var path = Path.Combine(configFolder, "splash.properties");
            if (File.Exists(path)) return;
            Directory.CreateDirectory(configFolder);
            File.WriteAllText(path,
                "enabled=true\n" +
                "showMemory=false\n" +
                "backgroundColor=0x050505\n" +
                "fontColor=0xF5F5F5\n" +
                "barColor=0xE0E0E0\n" +
                "barBorderColor=0x3A3A3A\n" +
                "barBackgroundColor=0x161616\n");
        }
        catch
        {
            // Cosmetic only — never block a launch over it.
        }
    }
}
