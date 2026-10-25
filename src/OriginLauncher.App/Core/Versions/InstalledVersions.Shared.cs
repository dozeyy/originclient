using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Versions;

// The launcher's one InstalledVersions, wired to the real data folder, the version catalog, the Origin jars
// bundled with this launcher and the 1.8.9 Forge layout. App-only (VersionManager and the loaders pull in
// CmlLib); the tests build their own instance over temp folders.
public sealed partial class InstalledVersions
{
    /// <summary>The instance the launcher uses. Creating it does no disk work; nothing is read until a Refresh call.</summary>
    public static InstalledVersions Shared { get; } = new(
        VersionCatalog.SupportedVersions,
        OriginPaths.VersionRoot,
        OriginPaths.ModsDir,
        VersionManager.BundledJarFor,
        LegacyForgeInstaller.LayoutFor);
}
