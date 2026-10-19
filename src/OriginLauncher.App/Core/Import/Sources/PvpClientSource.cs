using System.IO;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

// The PvP clients: Lunar Client, Feather Client, Badlion Client, LabyMod 4. None of them has instances like
// Prism or Modrinth. They play out of the normal .minecraft folder (worlds in saves/, resourcepacks/,
// shaderpacks/, screenshots/, servers.dat, options.txt) and keep two things of their own:
//   * their BUILT-IN mods (HUD, cosmetics, keystrokes...) and those mods' settings: closed code inside the
//     client, so nothing can carry them to Origin. The plan says so in one line and names Origin's own
//     equivalents (BuiltInModsNote);
//   * on the clients that allow it, the player's own FABRIC mods, in a folder per Minecraft version outside
//     .minecraft/mods. Each such folder with jars in it is one setup: GameDir = .minecraft (worlds, packs and
//     settings come from there), ModsDir = that folder (DetectedInstance.ModsDir).
//
// Where each client keeps them (public sources; Origin never installs or runs a client to look):
//   Lunar   %USERPROFILE%\.lunarclient. CONFIRMED on a real install (Will's PC, 2026-10-19, read-only listing):
//           profiles/<line>/ per version line ("1.8", "1.20", "1.21", "26") holds options.txt, optionsof.txt,
//           servers.dat, resourcepacks/, shaders/ and logs/; the player's own Fabric mods are in
//           profiles/<line>/mods/fabric-<version>/*.jar (fabric-1.21.1 and fabric-1.21.11 side by side under "1.21";
//           Lunar's 1.8 line has mods/ichor-1.8.9, its own loader, no Fabric). mods/fabric-<v>/dependencies/ is
//           Lunar's unpacked copy of those jars' nested libraries, never copied (the jars carry them). Worlds and
//           screenshots are in the "game directory" settings/launcher.json names (settings.gameDirectory, .minecraft
//           by default): only that one key is read. Custom mods are Fabric-only, 1.16.5+
//           (lunarclient.com/news/how-to-add-your-own-mods-to-lunar-client). Lunar's "mod profiles"
//           (settings/game/<name>/) are its own HUD layouts, never read; settings/game/accounts.json is a login file
//           the CredentialGuard refuses anyway. optionsLC.txt is Lunar's own and is not copied.
//   Feather %APPDATA%\.feather. NOT on Will's PC; its own-mods folder (user-mods/<version>) is a community report:
//           version-named folders under user-mods/ or mods/ are taken, nothing else.
//   LabyMod .minecraft/labymod-neo. NOT on Will's PC; LabyMod 4's Fabric mods: labymod-neo/fabric/<version>/mods
//           (community report). LabyMod addons are LabyMod-only and stay behind with its other built-ins.
//   Badlion %APPDATA%\Badlion Client holds the client; the game plays out of .minecraft (Badlion forum).
//           Badlion runs no outside mods, so there is nothing per-version to find.
//   Essential is a normal Fabric mod in a mods folder: it comes over as one (its essential/ folder, with
//           microsoft_accounts.json, is never read). Salwyrr and PvP Lounge: no public layout found; whatever
//           they keep in .minecraft is found as that folder.
//
// The .minecraft folder itself: the official launcher's detector lists it from launcher_profiles.json. A PvP-only
// player has no such file (the client installs the game itself), so the folder would be invisible; when it has
// game data it is listed here, named after the client whose folder is on this PC when there is exactly one.
public sealed class PvpClientSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.Lunar;

    // "1.21.1", "fabric-1.21.1", "fabric-26.2": a folder named for one Minecraft release ("fabric-26.2-snapshot-4" is not).
    private static readonly Regex VersionFolder = new(@"^(?:fabric-)?(?<mc>\d+\.\d+(?:\.\d+)?)$",
        RegexOptions.CultureInvariant | RegexOptions.IgnoreCase | RegexOptions.Compiled);

    // Lunar's other loaders' folders ("ichor-1.8.9"): they name the version line's game, not Fabric mods.
    private static readonly Regex AnyLoaderFolder = new(@"^[a-z]+-(?<mc>\d+\.\d+(?:\.\d+)?)$",
        RegexOptions.CultureInvariant | RegexOptions.IgnoreCase | RegexOptions.Compiled);

    // A Lunar profile named for its version line ("1.21", "26"): the setup is then named for the version alone.
    private static readonly Regex VersionLine = new(@"^\d+(\.\d+)*$", RegexOptions.CultureInvariant | RegexOptions.Compiled);

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) =>
        Task.Run(() =>
        {
            var minecraft = roots.OfficialMinecraftDir is { } mc && Directory.Exists(mc) ? mc : null;
            var members = new List<SetupMember>();
            Look(into, "Lunar Client", () =>
            {
                if (roots.LunarDir == null) return;
                var lunarGame = LunarGameDirectory(roots.LunarDir, fs) ?? minecraft;
                foreach (var profile in Sorted(fs.EnumerateDirectories(Path.Combine(roots.LunarDir, "profiles"))))
                {
                    ct.ThrowIfCancellationRequested();
                    var name = Path.GetFileName(profile);
                    var label = VersionLine.IsMatch(name) ? null : Pretty(name) + " ";
                    var mods = Path.Combine(profile, "mods");
                    var found = false;
                    foreach (var (dir, version) in VersionFolders(fs, mods))
                    {
                        found = true;
                        members.Add(Member(SourceKind.Lunar, label + version, profile, dir, version,
                            sharedGameDir: lunarGame, shaderPacksDir: Path.Combine(profile, "shaders")));
                    }
                    // A version line with settings but no Fabric mods (Lunar 1.8.9): its settings, servers and packs.
                    if (found || !(File.Exists(Path.Combine(profile, "options.txt")) || File.Exists(Path.Combine(profile, "servers.dat")))) continue;
                    var line = Sorted(fs.EnumerateDirectories(mods)).Select(d => AnyLoaderFolder.Match(Path.GetFileName(d)))
                        .FirstOrDefault(m => m.Success)?.Groups["mc"].Value;
                    members.Add(new SetupMember(new DetectedInstance
                    {
                        Kind = SourceKind.Lunar, Name = label + (line ?? name), GameDir = profile,
                        SharedGameDir = lunarGame, ShaderPacksDir = Path.Combine(profile, "shaders"), Confidence = Confidence.Inferred,
                    })
                    {
                        Metadata = line == null ? Array.Empty<VersionEvidence>()
                            : new[] { new VersionEvidence(EvidenceSource.LauncherMetadata, line, LoaderKind.None, null, null) { From = "mods folder name" } },
                    });
                }
            });
            Look(into, "Feather Client", () =>
            {
                if (roots.FeatherDir == null) return;
                foreach (var (dir, version) in VersionFolders(fs, Path.Combine(roots.FeatherDir, "user-mods"), Path.Combine(roots.FeatherDir, "mods")))
                    members.Add(Member(SourceKind.Feather, $"Fabric mods ({version})", minecraft ?? roots.FeatherDir, dir, version));
            });
            Look(into, "LabyMod", () =>
            {
                if (minecraft == null) return;
                foreach (var versionDir in Sorted(fs.EnumerateDirectories(Path.Combine(minecraft, "labymod-neo", "fabric"))))
                {
                    ct.ThrowIfCancellationRequested();
                    var match = VersionFolder.Match(Path.GetFileName(versionDir));
                    var mods = Path.Combine(versionDir, "mods");
                    if (match.Success && HasJars(fs, mods))
                        members.Add(Member(SourceKind.LabyMod, $"Fabric mods ({match.Groups["mc"].Value})", minecraft, mods, match.Groups["mc"].Value));
                }
            });

            if (minecraft != null && !File.Exists(Path.Combine(minecraft, "launcher_profiles.json")) && FolderEvidence.HasData(minecraft))
            {
                var clients = new List<SourceKind>();
                if (roots.LunarDir != null) clients.Add(SourceKind.Lunar);
                if (roots.FeatherDir != null) clients.Add(SourceKind.Feather);
                if (roots.BadlionDir != null) clients.Add(SourceKind.Badlion);
                if (Directory.Exists(Path.Combine(minecraft, "labymod-neo"))) clients.Add(SourceKind.LabyMod);
                members.Add(new SetupMember(new DetectedInstance
                {
                    Kind = clients.Count == 1 ? clients[0] : SourceKind.OfficialLauncher,
                    Name = "Minecraft folder", GameDir = minecraft, Confidence = Confidence.Inferred,
                }));
            }

            // No launcher record for the plain folder: its log, options and mods decide (as for Modrinth).
            SetupResolver.AddSetups(into, members, fs, ct);
        }, ct);

    /// <summary>
    /// The one line a PvP client's setup gets in "Not brought over": its built-in mods stay with it, and what
    /// Origin has instead. Null for every other launcher.
    /// </summary>
    public static (string Name, string Reason)? BuiltInModsNote(SourceKind kind)
    {
        var client = kind switch
        {
            SourceKind.Lunar => "Lunar Client",
            SourceKind.Feather => "Feather Client",
            SourceKind.Badlion => "Badlion Client",
            SourceKind.LabyMod => "LabyMod",
            _ => null,
        };
        if (client == null) return null;
        return ($"{client}'s built-in mods",
            $"{client}'s own mods (its HUD, cosmetics and their settings) are built into {client}, so they can't be moved. "
            + "Origin has its own: FPS, CPS, Key Strokes, Toggle Sprint and Sneak, Zoom, Freelook, Coordinates, Armor Status "
            + "and more, in the mod menu (Right Shift in game).");
    }

    // A client whose folder is damaged or unreadable never takes the others down (IInstanceSource rule).
    private static void Look(ScanResult into, string client, Action look)
    {
        try
        {
            look();
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            into.Notes.Add($"Couldn't look through {client}");
        }
    }

    // Version-named folders holding at least one jar, directly inside each of the given folders.
    private static IEnumerable<(string Dir, string Version)> VersionFolders(SourceFs fs, params string[] parents)
    {
        foreach (var parent in parents)
            foreach (var dir in Sorted(fs.EnumerateDirectories(parent)))
            {
                var match = VersionFolder.Match(Path.GetFileName(dir));
                if (match.Success && HasJars(fs, dir)) yield return (dir, match.Groups["mc"].Value);
            }
    }

    private static bool HasJars(SourceFs fs, string dir) =>
        fs.EnumerateFiles(dir, recursive: false).Any(p => p.EndsWith(".jar", StringComparison.OrdinalIgnoreCase));

    // Lunar's "game directory" (worlds, screenshots): settings/launcher.json's settings.gameDirectory, the ONE value
    // read from that file (it also holds tracking ids and the selected profile: never kept). Null when it says nothing usable.
    private static string? LunarGameDirectory(string lunarDir, SourceFs fs)
    {
        var text = fs.ReadHeadText(Path.Combine(lunarDir, "settings", "launcher.json"), 256 * 1024);
        if (text == null) return null;
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(text);
            if (doc.RootElement.ValueKind == System.Text.Json.JsonValueKind.Object
                && doc.RootElement.TryGetProperty("settings", out var settings)
                && settings.ValueKind == System.Text.Json.JsonValueKind.Object
                && settings.TryGetProperty("gameDirectory", out var dir)
                && dir.ValueKind == System.Text.Json.JsonValueKind.String
                && SourceHelpers.Normalize(dir.GetString()) is { } path && Directory.Exists(path))
                return path;
        }
        catch (System.Text.Json.JsonException) { }
        return null;
    }

    private static IEnumerable<string> Sorted(IEnumerable<string> dirs) => dirs.OrderBy(d => d, StringComparer.OrdinalIgnoreCase);

    private static string Pretty(string profile) =>
        profile.Length == 0 ? "Profile" : char.ToUpperInvariant(profile[0]) + profile[1..];

    // The folder name is the client's own statement of the version (a record, like a Prism component): the jars
    // inside are weighed against it by the usual judge, so a 1.21.1 folder full of 1.20.1 mods is "not sure".
    private static SetupMember Member(SourceKind kind, string name, string gameDir, string modsDir, string version,
        string? sharedGameDir = null, string? shaderPacksDir = null)
    {
        DateTimeOffset? changed = null;
        try { changed = new DateTimeOffset(Directory.GetLastWriteTimeUtc(modsDir), TimeSpan.Zero); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        return new SetupMember(new DetectedInstance
        {
            Kind = kind, Name = name, GameDir = gameDir, ModsDir = modsDir, McVersion = version, Loader = LoaderKind.Fabric,
            SharedGameDir = sharedGameDir, ShaderPacksDir = shaderPacksDir,
        })
        {
            Metadata = new[] { new VersionEvidence(EvidenceSource.LauncherMetadata, version, LoaderKind.Fabric, null, changed) { From = "mods folder name" } },
        };
    }
}
