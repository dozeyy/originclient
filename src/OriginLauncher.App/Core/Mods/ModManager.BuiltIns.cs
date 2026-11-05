using System.Collections.Concurrent;
using System.IO;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.Core.Mods;

// The launchers' half of ModManager.FindBuiltIns (Windows, Mac and Linux all link it; the unit tests don't, since it
// reaches VersionManager's bundled-jar table). Kept apart so ModManager.cs stays linkable on its own.
public static partial class ModManager
{
    // One read of the bundled jar per version and launcher run: the jar only changes with a launcher update (a restart).
    private static readonly ConcurrentDictionary<string, BuiltInModSet> BuiltInsByVersion = new(StringComparer.Ordinal);

    static partial void FindBuiltIns(string version, ref BuiltInModSet? builtIns)
    {
        try
        {
            builtIns = BuiltInsByVersion.GetOrAdd(version, v =>
            {
                var catalog = LauncherImportBackend.CatalogModsFor(v);
                var jar = VersionManager.BundledJarFor(v);
                if (jar == null || !File.Exists(jar)) return BuiltInModSet.Build(v, null, catalog);
                using var stream = new FileStream(jar, FileMode.Open, FileAccess.Read, FileShare.Read);
                return BuiltInModSet.Build(v, stream, catalog);
            });
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            builtIns = null; // the bundled jar couldn't be read: the check goes on as before, without built-ins
        }
    }
}
