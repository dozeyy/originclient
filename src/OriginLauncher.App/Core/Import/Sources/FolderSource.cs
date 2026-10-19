using System.IO;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

/// <summary>What Browse found in the folder the player picked.</summary>
public sealed class FolderInspection
{
    public List<DetectedInstance> Instances { get; } = new();

    /// <summary>Plain sentence for the player when nothing usable was found; null when there is at least one instance.</summary>
    public string? Message { get; set; }

    public const string NotAnInstance = "This folder doesn't look like a Minecraft instance";
}

// "Browse...": the player points at a folder and we work out what it is. It can
// be an instance's root (a launcher's instance folder that holds a .minecraft or
// minecraft subfolder), the game folder itself (options.txt, mods/, saves/ ...),
// or an official launcher folder (launcher_profiles.json). Anything else is
// "This folder doesn't look like a Minecraft instance" and gets no plan.
//
// A browsed folder has no launcher metadata of its own to trust, so its version
// and loader come from the log and the mods (D5) and are always Inferred.
// This is also how an instance of a launcher whose own detector can't find it
// (relocated data folder, portable MultiMC) is still importable.
public sealed class FolderSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.Folder;

    // Things only a game folder has. A bare "config" folder is deliberately not enough.
    private static readonly string[] GameDirMarkers = { "options.txt", "mods", "saves", "resourcepacks", "shaderpacks" };

    // DetectAsync is a no-op: Browse is driven by InspectAsync with the picked folder.
    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) => Task.CompletedTask;

    public static Task<FolderInspection> InspectAsync(string folder, SourceFs fs, CancellationToken ct) =>
        Task.Run(() => Inspect(folder, fs, ct), ct);

    public static FolderInspection Inspect(string folder, SourceFs fs, CancellationToken ct)
    {
        var result = new FolderInspection();
        var root = SourceHelpers.Normalize(folder);
        if (root == null || !Directory.Exists(root))
        {
            result.Message = FolderInspection.NotAnInstance;
            return result;
        }

        try
        {
            // An official launcher folder: its profiles are the candidates.
            if (File.Exists(Path.Combine(root, "launcher_profiles.json")))
            {
                var official = new ScanResult();
                OfficialLauncherSource.DetectIn(root, fs, official, ct);
                if (official.Instances.Count > 0)
                {
                    foreach (var instance in official.Instances) result.Instances.Add(instance with { Kind = SourceKind.Folder });
                    return result;
                }
            }

            // A launcher's own instance folder (or its game folder), picked by hand: that launcher's
            // metadata is still the best evidence, so a MultiMC-format folder reads like a scanned one.
            if (ReadLauncherInstance(root, fs, ct) is { } known)
            {
                result.Instances.Add(known);
                return result;
            }

            var gameDir = FindGameDir(root);
            if (gameDir == null)
            {
                result.Message = FolderInspection.NotAnInstance;
                return result;
            }

            var instanceRoot = IsInside(root, gameDir) && !SamePath(root, gameDir) ? root : gameDir;
            var name = NameFor(instanceRoot, gameDir);
            var instance2 = new DetectedInstance
            {
                Kind = SourceKind.Folder, Name = name, GameDir = gameDir, Confidence = Confidence.Inferred,
            };
            result.Instances.Add(SetupResolver.ResolveOne(new SetupMember(instance2), fs, ct));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            result.Instances.Clear();
            result.Message = FolderInspection.NotAnInstance;
        }
        return result;
    }

    private static DetectedInstance? ReadLauncherInstance(string root, SourceFs fs, CancellationToken ct)
    {
        var candidates = new List<string> { root };
        if (Path.GetFileName(root) is ".minecraft" or "minecraft" && Path.GetDirectoryName(root) is { Length: > 0 } parent)
            candidates.Add(parent);
        foreach (var folder in candidates)
        {
            var instance = PrismSource.ReadInstance(folder, fs, ct) ?? CurseForgeSource.ReadInstance(folder, fs, ct);
            if (instance != null) return instance;
        }
        return null;
    }

    /// <summary>The game folder of what the player picked: the folder itself, or its .minecraft / minecraft subfolder.</summary>
    public static string? FindGameDir(string root)
    {
        if (LooksLikeGameDir(root)) return root;
        foreach (var sub in new[] { ".minecraft", "minecraft" })
        {
            var candidate = Path.Combine(root, sub);
            if (Directory.Exists(candidate) && LooksLikeGameDir(candidate)) return candidate;
        }
        return null;
    }

    public static bool LooksLikeGameDir(string dir)
    {
        foreach (var marker in GameDirMarkers)
        {
            var path = Path.Combine(dir, marker);
            if (File.Exists(path) || Directory.Exists(path)) return true;
        }
        return File.Exists(Path.Combine(dir, "logs", "latest.log"));
    }

    // "MyPack\.minecraft" is named "MyPack", not ".minecraft".
    private static string NameFor(string instanceRoot, string gameDir)
    {
        var leaf = Path.GetFileName(Path.TrimEndingDirectorySeparator(instanceRoot));
        if (leaf is ".minecraft" or "minecraft" or "")
            leaf = Path.GetFileName(Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(instanceRoot)) ?? "");
        return string.IsNullOrWhiteSpace(leaf) ? "Imported folder" : leaf;
    }

    private static bool SamePath(string a, string b) =>
        string.Equals(Path.TrimEndingDirectorySeparator(a), Path.TrimEndingDirectorySeparator(b),
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);

    private static bool IsInside(string parent, string child) =>
        Path.GetFullPath(child).StartsWith(Path.TrimEndingDirectorySeparator(Path.GetFullPath(parent)) + Path.DirectorySeparatorChar,
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
}
