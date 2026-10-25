using System.IO;

namespace OriginLauncher.App.Core.GameFolders;

// Where every game folder lives (Will, 2026-10-12: several Minecraft windows at
// once, one per account). Pure path rules — no launcher state — so it is unit
// tested on Linux (src/OriginLauncher.Tests/GameFoldersTests.cs).
//
//   instances/<version>/                       the version's SHARED root: game jars,
//     versions/ libraries/ assets/ runtime/      libraries, assets and Java, downloaded
//     resources/ origin-verified.json            once per version (InstallVerification's
//                                                stamp lives here too)
//     mods/                                      the version's ONE mods folder, shared by
//                                                every account (Will, 2026-10-12) — the
//                                                game loads it via fabric.modsFolder
//                                                (SharedMods has the why and the merge;
//                                                Forge 1.8.9 can't, so it gets a copy
//                                                in the account's mods/: SharedMods.Mirror)
//     profiles/<id>/mods/                       one MOD PROFILE's own jars (Core/Profiles): the
//                                                player's named per-server mod sets. Shared by
//                                                every account like mods/; a launch copies them
//                                                into the account's own launch folder.
//     accounts/<account>/                       one ACCOUNT's game folder (--gameDir):
//       options.txt servers.dat saves/ config/   its own settings, worlds, servers and
//       logs/ screenshots/ ...                   mod configs
//
// <account> is the Minecraft profile UUID, lower-case without dashes (stable —
// a gamertag can change), or "offline" for Settings -> Developer -> offline test
// mode. Each account folder is a complete .minecraft apart from mods/, so a game
// launched there never touches another account's settings or worlds, and two
// accounts can play at once (on the same version too: they share the mods).
public static class InstanceLayout
{
    public const string AccountsFolder = "accounts";

    /// <summary>The version's shared mods folder, directly in its root.</summary>
    public const string ModsFolder = "mods";

    /// <summary>The folder holding a version's mod profiles, directly in its root.</summary>
    public const string ProfilesFolder = "profiles";

    /// <summary>The game folder used by offline test mode (no account signed in).</summary>
    public const string OfflineKey = "offline";

    // What stays in the version's shared root. Everything else found at the top
    // of a version folder is one account's game data (the pre-accounts layout
    // kept it all there) — see InstanceMigration.
    private static readonly HashSet<string> Shared = new(StringComparer.OrdinalIgnoreCase)
    {
        "versions", "libraries", "assets", "runtime", "resources",
        AccountsFolder, ModsFolder, ProfilesFolder, "origin-verified.json",
    };

    public static bool IsSharedEntry(string name) => Shared.Contains(name);

    /// <summary>The 1.8.9 performance presets' undo record, directly in an account's game folder (Core/Presets).</summary>
    public const string PresetUndoFileName = "origin-preset-undo.json";

    public static string PresetUndoFile(string gameDir) => Path.Combine(gameDir, PresetUndoFileName);

    public static string VersionRoot(string instancesRoot, string version) =>
        Path.Combine(instancesRoot, version);

    public static string AccountsDir(string instancesRoot, string version) =>
        Path.Combine(VersionRoot(instancesRoot, version), AccountsFolder);

    public static string GameDir(string instancesRoot, string version, string accountKey) =>
        Path.Combine(AccountsDir(instancesRoot, version), accountKey);

    /// <summary>The mods folder every account's game of this version loads.</summary>
    public static string ModsDir(string instancesRoot, string version) =>
        Path.Combine(VersionRoot(instancesRoot, version), ModsFolder);

    /// <summary>All of a version's mod profiles (one folder per profile id).</summary>
    public static string ProfilesDir(string instancesRoot, string version) =>
        Path.Combine(VersionRoot(instancesRoot, version), ProfilesFolder);

    /// <summary>One profile's folder: instances/&lt;version&gt;/profiles/&lt;id&gt;. The id must be a ProfileRules.IsValidId one.</summary>
    public static string ProfileDir(string instancesRoot, string version, string profileId) =>
        Path.Combine(ProfilesDir(instancesRoot, version), profileId);

    /// <summary>The mods a profile loads: instances/&lt;version&gt;/profiles/&lt;id&gt;/mods.</summary>
    public static string ProfileModsDir(string instancesRoot, string version, string profileId) =>
        Path.Combine(ProfileDir(instancesRoot, version, profileId), ModsFolder);

    /// <summary>The shared mods folder for an account game folder (instances/&lt;version&gt;/accounts/&lt;account&gt;).</summary>
    public static string ModsDirForGameDir(string gameDir)
    {
        var accountDir = Path.TrimEndingDirectorySeparator(gameDir);
        var versionRoot = Path.GetDirectoryName(Path.GetDirectoryName(accountDir)) ?? accountDir;
        return Path.Combine(versionRoot, ModsFolder);
    }

    /// <summary>
    /// The folder key for an account id (a Minecraft profile UUID, with or
    /// without dashes). Null/empty = offline test mode. Anything that is not a
    /// plain letter/digit is dropped so a key is always one safe folder name.
    /// </summary>
    public static string KeyFor(string? accountId)
    {
        if (string.IsNullOrWhiteSpace(accountId)) return OfflineKey;
        var chars = accountId.Trim().ToLowerInvariant().Where(char.IsAsciiLetterOrDigit).ToArray();
        // A key that sanitises to nothing must still never collide with a real
        // one or with offline mode.
        return chars.Length == 0 ? "unknown" : new string(chars);
    }

    /// <summary>True for a folder name that can be an account's game folder (not a temp/seeding folder).</summary>
    public static bool IsAccountKey(string name) =>
        name.Length > 0 && name.All(char.IsAsciiLetterOrDigit) && name == name.ToLowerInvariant();

    /// <summary>
    /// Natives are extracted per account: CmlLib wipes and re-extracts the
    /// natives folder on every launch, and a game already running from it
    /// holds those DLLs open — sharing one folder would fail (or break) the
    /// second account's launch of the same version.
    /// </summary>
    public static string NativesDir(string versionsDir, string versionId, string accountKey) =>
        Path.Combine(versionsDir, versionId, "natives-" + accountKey);
}
