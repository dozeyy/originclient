using System.IO;

namespace OriginLauncher.App.Core.Compat;

/// <summary>One file a <see cref="LaunchSwap"/> adds as Origin's.</summary>
/// <param name="Path">Where it is (or will be, once fetched).</param>
/// <param name="Expected">What it will say about itself; used only while the file is not there yet.</param>
public sealed record SwapFile(string Path, ModInfo Expected);

/// <summary>
/// How an alternate component configuration (Core/Components) changes Origin's side of a launch set, so the
/// pre-launch check can judge it before anything is fetched or copied: Origin's jars with
/// <see cref="ReplacedPrefixes"/> leave the set, <see cref="ReplacedIds"/> are taken out of what Origin's jars nest
/// (the top-level copy wins at runtime, docs/overhaul/spike-sodium-iris.md), and <see cref="Files"/> join as Origin's.
/// </summary>
public sealed record LaunchSwap(
    string Id, IReadOnlyList<string> ReplacedPrefixes, IReadOnlyCollection<string> ReplacedIds, IReadOnlyList<SwapFile> Files)
{
    public bool Replaces(string fileName) =>
        ReplacedPrefixes.Any(p => fileName.StartsWith(p, StringComparison.OrdinalIgnoreCase));

    /// <summary><paramref name="info"/> without the nested copies this swap replaces.</summary>
    public ModInfo WithoutReplaced(ModInfo info) =>
        info.Nested.Any(n => ReplacedIds.Contains(n.Id))
            ? info with { Nested = info.Nested.Where(n => !ReplacedIds.Contains(n.Id)).ToList() }
            : info;

    public SwapFile? FileAt(string path) =>
        Files.FirstOrDefault(f => string.Equals(f.Path, path, StringComparison.OrdinalIgnoreCase));

    public static string NameOf(SwapFile file) => System.IO.Path.GetFileName(file.Path);
}
