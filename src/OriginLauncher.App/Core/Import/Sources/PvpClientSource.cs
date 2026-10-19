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
//   Lunar   %USERPROFILE%\.lunarclient. Custom mods are Fabric-only, 1.16.5+, "profile specific", opened with
//           the folder button in Lunar's mods screen (lunarclient.com/news/how-to-add-your-own-mods-to-lunar-client);
//           resource packs are .minecraft/resourcepacks (lunarclient.com/faq). The exact folder is not documented:
//           community tools put it at .lunarclient/profiles/<profile>/mods/fabric-<version>, so this looks for a
//           version-named folder (fabric-1.21.1 or 1.21.1) up to two levels under each profile. Lunar's
//           "mod profiles" (.lunarclient/settings/game) are its own HUD layouts, never read, and
//           settings/game/accounts.json is a login file the CredentialGuard refuses anyway.
//   Feather %APPDATA%\.feather. Its own-mods folder (user-mods/<version>) is a community report, not documented:
//           version-named folders under user-mods/ or mods/ are taken, nothing else.
//   LabyMod .minecraft/labymod-neo. LabyMod 4's Fabric mods: labymod-neo/fabric/<version>/mods (community
//           report). LabyMod addons are LabyMod-only and stay behind with its other built-ins.
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

    // "1.21.1", "fabric-1.21.1", "fabric-1.8": a folder named for one Minecraft release.
    private static readonly Regex VersionFolder = new(@"^(?:fabric-)?(?<mc>\d+\.\d+(?:\.\d+)?)$",
        RegexOptions.CultureInvariant | RegexOptions.IgnoreCase | RegexOptions.Compiled);

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) =>
        Task.Run(() =>
        {
            var minecraft = roots.OfficialMinecraftDir is { } mc && Directory.Exists(mc) ? mc : null;
            var members = new List<SetupMember>();
            Look(into, "Lunar Client", () =>
            {
                if (roots.LunarDir == null) return;
                foreach (var profile in Sorted(fs.EnumerateDirectories(Path.Combine(roots.LunarDir, "profiles"))))
                {
                    ct.ThrowIfCancellationRequested();
                    var name = Path.GetFileName(profile);
                    foreach (var (dir, version) in VersionFolders(fs, profile, Path.Combine(profile, "mods")))
                        members.Add(Member(SourceKind.Lunar, $"{Pretty(name)} ({version})", minecraft ?? profile, dir, version));
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

    private static IEnumerable<string> Sorted(IEnumerable<string> dirs) => dirs.OrderBy(d => d, StringComparer.OrdinalIgnoreCase);

    private static string Pretty(string profile) =>
        profile.Length == 0 ? "Profile" : char.ToUpperInvariant(profile[0]) + profile[1..];

    // The folder name is the client's own statement of the version (a record, like a Prism component): the jars
    // inside are weighed against it by the usual judge, so a 1.21.1 folder full of 1.20.1 mods is "not sure".
    private static SetupMember Member(SourceKind kind, string name, string gameDir, string modsDir, string version)
    {
        DateTimeOffset? changed = null;
        try { changed = new DateTimeOffset(Directory.GetLastWriteTimeUtc(modsDir), TimeSpan.Zero); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        return new SetupMember(new DetectedInstance
        {
            Kind = kind, Name = name, GameDir = gameDir, ModsDir = modsDir, McVersion = version, Loader = LoaderKind.Fabric,
        })
        {
            Metadata = new[] { new VersionEvidence(EvidenceSource.LauncherMetadata, version, LoaderKind.Fabric, null, changed) { From = "mods folder name" } },
        };
    }
}
