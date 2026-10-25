using System.IO;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI;

/// <summary>
/// An <see cref="InstalledVersions"/> over a temp folder with a chosen state per version, for the selector's UI tests and
/// its screenshots (render mode only; the launcher never calls this). The files are the real thing the disk check looks
/// for, so the states come out of the production classifier rather than being faked past it:
///   Installed     client + Fabric loader + Origin jar identical to the bundled one
///   UpdateReady   the same, but the bundled jar differs from the installed one
///   Partial       the client files only
///   NotInstalled  no folder
/// Versions not listed are NotInstalled.
/// </summary>
internal static class SelectorFakeInstalls
{
    internal static InstalledVersions Build(string folder, IReadOnlyDictionary<string, InstallState> states)
    {
        var instances = Path.Combine(folder, "instances");
        var bundled = Path.Combine(folder, "bundled");
        Directory.CreateDirectory(bundled);

        string Root(string version) => Path.Combine(instances, version);
        string Jar(string version) => Path.Combine(bundled, "originclient-" + version + ".jar");

        foreach (var version in VersionCatalog.SupportedVersions)
        {
            File.WriteAllBytes(Jar(version), new byte[300]);
            File.SetLastWriteTimeUtc(Jar(version), Stamp);
            if (!states.TryGetValue(version, out var state) || state is InstallState.NotInstalled or InstallState.Unknown) continue;

            var root = Root(version);
            Put(Path.Combine(root, "versions", version, version + ".json"), 8);
            Put(Path.Combine(root, "versions", version, version + ".jar"), 100);
            Directory.CreateDirectory(Path.Combine(root, "mods"));
            if (state == InstallState.Partial) continue;

            var loader = $"fabric-loader-0.16.14-{version}";
            Put(Path.Combine(root, "versions", loader, loader + ".json"), 8);
            var installed = Put(Path.Combine(root, "mods", InstalledVersions.OriginJarFileName), state == InstallState.UpdateReady ? 200 : 300);
            File.SetLastWriteTimeUtc(installed, Stamp);
        }

        return new InstalledVersions(
            VersionCatalog.SupportedVersions,
            Root,
            v => Path.Combine(Root(v), "mods"),
            Jar,
            _ => null);
    }

    private static readonly DateTime Stamp = new(2026, 10, 1, 12, 0, 0, DateTimeKind.Utc);

    private static string Put(string path, int bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, new byte[bytes]);
        return path;
    }
}
