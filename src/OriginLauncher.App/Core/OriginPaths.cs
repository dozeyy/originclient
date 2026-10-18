using System.IO;
using OriginLauncher.App.Core.GameFolders;

namespace OriginLauncher.App.Core;

public static class OriginPaths
{
    public static string Root { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OriginLauncher");

    // Game folders: instances/<version>/ holds the version's shared game files,
    // instances/<version>/accounts/<account>/ is one account's own game folder
    // (Core/GameFolders/InstanceLayout.cs has the full layout and why).
    public static string Instances => Path.Combine(Root, "instances");

    /// <summary>A version's shared root (game jars, libraries, assets, Java).</summary>
    public static string VersionRoot(string version) => InstanceLayout.VersionRoot(Instances, version);

    /// <summary>One account's game folder for a version (its options, worlds, servers, configs).</summary>
    public static string GameDir(string version, string accountKey) =>
        InstanceLayout.GameDir(Instances, version, accountKey);

    /// <summary>A version's mods folder, shared by every account (Core/GameFolders/SharedMods.cs).</summary>
    public static string ModsDir(string version) => InstanceLayout.ModsDir(Instances, version);

    /// <summary>All mod profiles of a version (Core/Profiles); each is a folder named by its id.</summary>
    public static string ProfilesDir(string version) => InstanceLayout.ProfilesDir(Instances, version);

    /// <summary>One mod profile's folder, instances/&lt;version&gt;/profiles/&lt;id&gt;.</summary>
    public static string ProfileDir(string version, string profileId) =>
        InstanceLayout.ProfileDir(Instances, version, profileId);

    /// <summary>The jars one mod profile loads (the Mods page edits this while the profile is selected).</summary>
    public static string ProfileModsDir(string version, string profileId) =>
        InstanceLayout.ProfileModsDir(Instances, version, profileId);

    // Which account inherited the old one-folder-per-version game data
    // (Core/GameFolders/InstanceMigration.cs).
    public static string AccountMigrationMarker => Path.Combine(Instances, "account-migration.json");
    public static string Accounts => Path.Combine(Root, "accounts");
    public static string Logs => Path.Combine(Root, "logs");

    // Persistent home for the GPU driver's compiled-shader cache (see
    // Core/ShaderCache.cs) — kept under our data root so it survives across
    // instances and game versions, maximising cache hits between sessions.
    public static string ShaderCache => Path.Combine(Root, "shader-cache");

    // Home banner (UI/Controls/PromoBanner): the last good remote promo feed,
    // so the banner shows Will's current promos even when offline.
    public static string PromoFeedCache => Path.Combine(Root, "cache", "promos.json");

    // Settings Sync (Core/SettingsSync.cs): every synced setting with when it
    // last changed, plus what each instance last had — so the player's
    // settings follow them across versions. Rebuilt from the instances
    // whenever it is missing, so deleting it never loses a setting.
    // One store per account (each account's game folders sync among
    // themselves). settings-sync.json is the pre-accounts single store, moved
    // to the inheriting account's file by the game-folder migration.
    public static string SettingsSyncStoreFor(string accountKey) =>
        Path.Combine(Root, "settings-sync", accountKey + ".json");

    public static string LegacySettingsSyncStore => Path.Combine(Root, "settings-sync.json");

    // The banner's ad-network WebView2 profile (cookies, storage, browser
    // cache). Deliberately its OWN user-data folder: it never shares a
    // browser process, cookie jar or lock with the Microsoft sign-in WebView2
    // (MicrosoftSignInPanel, SignInWebViewData below).
    public static string BannerWebViewData => Path.Combine(Root, "webview", "banner");

    // The Microsoft sign-in WebView2's user-data folder. The panel runs
    // InPrivate, so no cookies or session persist here; the browser process
    // still needs a writable working folder, and it belongs under our root.
    public static string SignInWebViewData => Path.Combine(Root, "webview", "signin");

    // WebView2's DEFAULT user-data folder (<exe name>.WebView2 next to the exe),
    // which the sign-in panel used before 2026-10-13. It kept the first
    // account's long-lived Microsoft session cookies, which made every later
    // "Add Microsoft Account" sign in as that account again, so App deletes it
    // at startup. Null unless running as our own apphost exe (e.g. under
    // `dotnet <dll>` the "exe" is dotnet.exe), so no other folder is targeted.
    public static string? LegacySignInWebViewData
    {
        get
        {
            var exe = Environment.ProcessPath;
            var exeDir = string.IsNullOrEmpty(exe) ? null : Path.GetDirectoryName(exe);
            if (exeDir == null || !string.Equals(
                    Path.GetFullPath(exeDir).TrimEnd(Path.DirectorySeparatorChar),
                    Path.GetFullPath(AppContext.BaseDirectory).TrimEnd(Path.DirectorySeparatorChar),
                    StringComparison.OrdinalIgnoreCase))
                return null;
            return Path.Combine(exeDir, Path.GetFileName(exe) + ".WebView2");
        }
    }

    // Read-only assets shipped alongside the exe (see the Content items in
    // OriginLauncher.App.csproj that copy them out of each src/mods module's
    // Gradle build output) — not user data, so they live next to the app
    // binary rather than under %LocalAppData%. Resolved from the running
    // process's own directory so this works identically from a dev build and
    // from wherever a setup installer actually places the app.
    //
    // One jar per supported Minecraft API family (originclient-1.20.jar,
    // originclient-1.21.1.jar, ...). VersionManager's Origin-build registry
    // maps each offered MC version to the matching filename here.
    public static string BundledOriginClientDir => Path.Combine(
        AppContext.BaseDirectory, "Bundled", "OriginClient");

    public static string BundledOriginClientJar(string jarFileName) =>
        Path.Combine(BundledOriginClientDir, jarFileName);

    public static void EnsureScaffold()
    {
        Directory.CreateDirectory(Instances);
        Directory.CreateDirectory(Accounts);
        Directory.CreateDirectory(Logs);
        // optifine-cache under Root holds OptiFine for 1.8.9 (Forge +
        // OptiFine, live again 2026-10-13), downloaded once per machine. It's
        // owned by Core/Loaders/OptiFineInstaller.cs, which creates it.
    }
}
