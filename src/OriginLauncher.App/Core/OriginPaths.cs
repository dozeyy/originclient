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

    /// <summary>One account's game folder for a version (its options, worlds, servers, configs, mods).</summary>
    public static string GameDir(string version, string accountKey) =>
        InstanceLayout.GameDir(Instances, version, accountKey);

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
    // (MicrosoftSignInPanel, which uses WebView2's default folder).
    public static string BannerWebViewData => Path.Combine(Root, "webview", "banner");

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
        // optifine-cache under Root is live again — the legacy versions
        // (1.8.9/1.12.2, Forge + OptiFine) returned 2026-07-14. It's owned by
        // Core/Loaders/OptiFineInstaller.cs.
    }
}
