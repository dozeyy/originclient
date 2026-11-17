using System.Collections.Concurrent;
using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Loaders;

namespace OriginLauncher.App.Core.Renderers;

/// <summary>What <see cref="VulkanRendererFiles.EnsureAsync"/> put in place: the jars to load, or why there are none.</summary>
public sealed record VulkanFiles(IReadOnlyList<string>? Jars, string? Problem)
{
    public bool Ready => Jars != null;
}

/// <summary>
/// The files behind 1.21.1's Vulkan graphics (VulkanMode has the rules). Every path is a parameter, so the tests run on
/// temp folders and a fake HTTP handler; VersionManager and Settings pass OriginPaths.BundledVulkanDir and
/// OriginPaths.RendererDir("1.21.1", "vulkan").
///
/// The renderer folder is add-only or rename-swap, like every folder a running game may hold jars from: a new file is
/// written beside as "&lt;name&gt;.download" and renamed in; an existing file is never rewritten. Launches load hard links
/// of these (mods-vulkan), so another account's running Vulkan game keeps its own links whatever happens here.
/// </summary>
public static class VulkanRendererFiles
{
    /// <summary>
    /// Origin's bundled Vulkan jars in <paramref name="bundledDir"/>, one per VulkanMode.BundledPatterns (the newest
    /// name when a pattern matches several), or null when any of them is missing: then Vulkan graphics is unavailable.
    /// </summary>
    public static IReadOnlyList<string>? BundledJars(string bundledDir)
    {
        try
        {
            if (!Directory.Exists(bundledDir)) return null;
            var found = new List<string>();
            foreach (var pattern in VulkanMode.BundledPatterns)
            {
                var match = Directory.EnumerateFiles(bundledDir, pattern)
                    .Where(p => !p.EndsWith("-sources.jar", StringComparison.OrdinalIgnoreCase)
                                && !p.EndsWith("-dev.jar", StringComparison.OrdinalIgnoreCase))
                    .OrderByDescending(p => Path.GetFileName(p), StringComparer.OrdinalIgnoreCase)
                    .FirstOrDefault();
                if (match == null) return null;
                found.Add(match);
            }
            return found;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// The player's mods among <paramref name="jars"/> that keep Vulkan off (VulkanMode.BlockerFor), read from each
    /// jar's own fabric.mod.json. <paramref name="isOrigins"/> (ModManager.IsManagedFile) leaves Origin's own jars out:
    /// Origin's 1.21.1 jar carries Sodium and Iris by design and is handled by the disableModIds switch instead.
    /// Off the UI thread: it opens every jar (top-level metadata only, no nested jars).
    /// </summary>
    public static IReadOnlyList<VulkanBlocker> ScanBlockers(IEnumerable<string> jars, Func<string, bool> isOrigins)
    {
        var mods = new List<ModInfo>();
        foreach (var path in jars)
        {
            if (!path.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue;
            try
            {
                if (isOrigins(path)) continue;
                using var stream = File.OpenRead(path);
                mods.Add(ModMetadataReader.Read(stream, Path.GetFileName(path), readNested: false));
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Locked or gone: nothing to judge, the launch's own checks see it.
            }
        }
        return VulkanMode.Blockers(mods);
    }

    /// <summary>The .jar files directly in <paramref name="folder"/>, or none when it isn't there.</summary>
    public static IEnumerable<string> JarsIn(string? folder)
    {
        if (folder == null) return Array.Empty<string>();
        try
        {
            return Directory.Exists(folder)
                ? Directory.EnumerateFiles(folder, "*.jar").ToList()
                : Array.Empty<string>();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return Array.Empty<string>();
        }
    }

    /// <summary>
    /// Puts VulkanMod (downloaded once, moved in only on a SHA-512 match with <paramref name="pin"/>) and Origin's
    /// bundled jars into <paramref name="rendererDir"/>, and returns the full paths a Vulkan launch loads. Fail-soft:
    /// a missing bundle, no internet on the first Vulkan launch or a bad download returns a Problem (the launch then
    /// uses normal graphics); only a cancellation is rethrown.
    /// </summary>
    public static async Task<VulkanFiles> EnsureAsync(
        HttpClient http, PerfMod pin, string bundledDir, string rendererDir, CancellationToken ct)
    {
        var bundled = BundledJars(bundledDir);
        if (bundled == null) return new VulkanFiles(null, VulkanMode.MissingJarsLine);
        try
        {
            Directory.CreateDirectory(rendererDir);
            var jars = new List<string>();

            var vulkanMod = Path.Combine(rendererDir, pin.FileName);
            if (!Verified(vulkanMod, pin))
            {
                // A file under the pin's name that does not hash to it (damaged, swapped): out of the way first. A
                // running game holds only its own hard link, so the delete never touches its copy.
                if (File.Exists(vulkanMod)) File.Delete(vulkanMod);
                var result = await ModrinthFileGuard.TryDownloadAsync(http, pin.Url, pin.FileName, pin.Sha512, rendererDir, ct)
                    .ConfigureAwait(false);
                if (result != ModrinthDownloadResult.Installed)
                    return new VulkanFiles(null, VulkanMode.SetupFailedLine);
                RememberVerified(vulkanMod);
            }
            jars.Add(vulkanMod);

            foreach (var source in bundled)
                jars.Add(CopyIn(source, rendererDir));
            return new VulkanFiles(jars, null);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new VulkanFiles(null, VulkanMode.SetupFailedLine);
        }
    }

    // A bundled jar into the renderer folder under its own (versioned) name: kept when the same length and time are
    // already there, otherwise copied beside and renamed in. A new launcher build brings a new name, so an older jar
    // is never overwritten while a game may hold a link to it.
    private static string CopyIn(string source, string rendererDir)
    {
        var dest = Path.Combine(rendererDir, Path.GetFileName(source));
        var from = new FileInfo(source);
        var to = new FileInfo(dest);
        if (to.Exists && to.Length == from.Length && to.LastWriteTimeUtc == from.LastWriteTimeUtc) return dest;
        var temp = dest + ".download";
        File.Copy(source, temp, overwrite: true);
        File.SetLastWriteTimeUtc(temp, from.LastWriteTimeUtc);
        File.Move(temp, dest, overwrite: true);
        return dest;
    }

    // VulkanMod already in place: hashed once per launcher run (and again when its size or time changes), ~20 MB.
    private static readonly ConcurrentDictionary<string, (long, long)> VerifiedStamps = new(StringComparer.OrdinalIgnoreCase);

    private static bool Verified(string path, PerfMod pin)
    {
        var file = new FileInfo(path);
        if (!file.Exists) return false;
        var stamp = (file.Length, file.LastWriteTimeUtc.Ticks);
        if (VerifiedStamps.TryGetValue(file.FullName, out var known) && known == stamp) return true;
        if (!pin.Matches(path)) return false;
        VerifiedStamps[file.FullName] = stamp;
        return true;
    }

    private static void RememberVerified(string path)
    {
        var file = new FileInfo(path);
        if (file.Exists) VerifiedStamps[file.FullName] = (file.Length, file.LastWriteTimeUtc.Ticks);
    }
}
