using System.Globalization;
using System.IO;
using System.Text.Json;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

// Prism Launcher and MultiMC (same on-disk layout):
//   <data root>/prismlauncher.cfg   (legacy polymc.cfg / multimc.cfg)  INI; InstanceDir = instances folder,
//                                   default "instances", relative to the data root or absolute
//   <instances>/<id>/instance.cfg   INI: name, lastLaunchTime (epoch MILLISECONDS, 0 = never),
//                                   totalTimePlayed (SECONDS)
//   <instances>/<id>/mmc-pack.json  {"formatVersion":1,"components":[{uid,version,...}]}:
//                                   net.minecraft = the Minecraft version; the loader is
//                                   net.fabricmc.fabric-loader / org.quiltmc.quilt-loader /
//                                   net.minecraftforge / net.neoforged
//   game dir                        <id>/.minecraft if it exists and <id>/minecraft does not, else <id>/minecraft
// There is no "pinned" notion in either launcher. Version and loader come from mmc-pack.json
// (Exact), cross-checked by SetupResolver against the instance's latest.log and mods: the pack
// file's mtime is "when the launcher last changed it", so a newer log that disagrees wins the
// preselection but the setup is marked not certain. A formatVersion other than 1 is "Couldn't
// read details" (never guessed at); a damaged pack file falls back to the log and mods. Never an exception.
public sealed class PrismSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.Prism;

    private static readonly string[] ConfigNames = { "prismlauncher.cfg", "polymc.cfg", "multimc.cfg" };
    private static readonly HashSet<string> InstanceDirKey = new(StringComparer.Ordinal) { "InstanceDir" };
    private static readonly HashSet<string> InstanceKeys = new(StringComparer.Ordinal)
    {
        "name", "lastLaunchTime", "totalTimePlayed",
    };

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) =>
        Task.Run(() =>
        {
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var members = new List<SetupMember>();
            foreach (var root in new[] { roots.PrismDataDir, roots.MultiMcDir })
            {
                if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) continue;
                foreach (var instanceDir in InstanceFolders(root, fs))
                {
                    ct.ThrowIfCancellationRequested();
                    if (!seen.Add(instanceDir)) continue;
                    if (ReadMember(instanceDir, fs) is { } member) members.Add(member);
                }
            }
            SetupResolver.AddSetups(into, members, fs, ct);
        }, ct);

    /// <summary>The instance folders under a data root: every direct child of the instances folder that holds an instance.cfg.</summary>
    public static IEnumerable<string> InstanceFolders(string dataRoot, SourceFs fs)
    {
        var instances = Path.Combine(dataRoot, "instances");
        foreach (var name in ConfigNames)
        {
            var cfg = Path.Combine(dataRoot, name);
            if (!File.Exists(cfg)) continue;
            var values = SourceHelpers.ReadIni(cfg, fs, InstanceDirKey);
            if (values != null && values.TryGetValue("InstanceDir", out var dir) && dir.Length > 0)
                instances = Path.IsPathRooted(dir) ? dir : Path.Combine(dataRoot, dir);
            break;
        }
        return fs.EnumerateDirectories(instances)
            .Where(d => File.Exists(Path.Combine(d, "instance.cfg")))
            .OrderBy(d => d, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    /// <summary>
    /// One instance folder (the folder holding instance.cfg), or null when it isn't one.
    /// Also used by Browse, so a MultiMC-format folder picked by hand reads the same.
    /// </summary>
    public static DetectedInstance? ReadInstance(string instanceDir, SourceFs fs, CancellationToken ct) =>
        ReadMember(instanceDir, fs) is { } member ? SetupResolver.ResolveOne(member, fs, ct) : null;

    /// <summary>The launcher's own description of one instance folder (not yet weighed against its log and mods).</summary>
    public static SetupMember? ReadMember(string instanceDir, SourceFs fs)
    {
        var cfg = Path.Combine(instanceDir, "instance.cfg");
        if (!File.Exists(cfg)) return null;

        var folderName = Path.GetFileName(Path.TrimEndingDirectorySeparator(instanceDir));
        var dotMinecraft = Path.Combine(instanceDir, ".minecraft");
        var plain = Path.Combine(instanceDir, "minecraft");
        var gameDir = Directory.Exists(dotMinecraft) && !Directory.Exists(plain) ? dotMinecraft : plain;

        var values = SourceHelpers.ReadIni(cfg, fs, InstanceKeys);
        var instance = new DetectedInstance
        {
            Kind = SourceKind.Prism,
            Name = values != null && values.TryGetValue("name", out var name) && name.Length > 0 ? name : folderName,
            GameDir = gameDir,
        };
        if (values == null) return new SetupMember(SourceHelpers.Unreadable(instance)) { NoInference = true };

        if (values.TryGetValue("totalTimePlayed", out var played)
            && long.TryParse(played, NumberStyles.None, CultureInfo.InvariantCulture, out var seconds))
            instance = instance with { PlaytimeSeconds = seconds };
        if (values.TryGetValue("lastLaunchTime", out var launched)
            && long.TryParse(launched, NumberStyles.None, CultureInfo.InvariantCulture, out var millis) && millis > 0)
        {
            try { instance = instance with { LastPlayed = DateTimeOffset.FromUnixTimeMilliseconds(millis) }; }
            catch (ArgumentOutOfRangeException) { /* a nonsense timestamp is just "unknown" */ }
        }

        var packPath = Path.Combine(instanceDir, "mmc-pack.json");
        var pack = ReadPack(packPath, fs, out var rejected);
        if (rejected) return new SetupMember(SourceHelpers.Unreadable(instance)) { NoInference = true };
        if (pack == null || (pack.McVersion == null && !pack.HasLoaderComponent))
            return new SetupMember(instance); // no usable component list: the folder's log and mods decide (always Inferred)

        // The pack file's mtime is when Prism last changed the components (EvidenceJudge V3 compares it with the log's).
        DateTimeOffset? changed = null;
        try { changed = new DateTimeOffset(File.GetLastWriteTimeUtc(packPath), TimeSpan.Zero); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        var statement = new VersionEvidence(EvidenceSource.LauncherMetadata, pack.McVersion, pack.Loader, pack.LoaderVersion, changed)
        {
            From = instance.Name,
        };
        return new SetupMember(instance) { Metadata = new[] { statement } };
    }

    private sealed record Pack(string? McVersion, LoaderKind Loader, string? LoaderVersion, bool HasLoaderComponent);

    private static Pack? ReadPack(string path, SourceFs fs, out bool rejected)
    {
        rejected = false;
        if (!File.Exists(path)) return null;
        var text = fs.ReadText(path);
        if (text == null) return null;
        try
        {
            using var doc = JsonDocument.Parse(text, new JsonDocumentOptions { AllowTrailingCommas = true });
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (!root.TryGetProperty("formatVersion", out var format) || format.ValueKind != JsonValueKind.Number
                || !format.TryGetInt32(out var version) || version != 1)
            {
                rejected = true; // a pack format this code doesn't know: don't guess at it
                return null;
            }
            if (!root.TryGetProperty("components", out var components) || components.ValueKind != JsonValueKind.Array) return null;

            string? mc = null, loaderVersion = null;
            var loader = LoaderKind.None;
            foreach (var component in components.EnumerateArray())
            {
                if (component.ValueKind != JsonValueKind.Object) continue;
                var uid = component.TryGetProperty("uid", out var u) && u.ValueKind == JsonValueKind.String ? u.GetString() : null;
                var v = component.TryGetProperty("version", out var ver) && ver.ValueKind == JsonValueKind.String ? ver.GetString() : null;
                switch (uid)
                {
                    case "net.minecraft": mc = v; break;
                    case "net.fabricmc.fabric-loader": loader = LoaderKind.Fabric; loaderVersion = v; break;
                    case "org.quiltmc.quilt-loader": loader = LoaderKind.Quilt; loaderVersion = v; break;
                    case "net.minecraftforge": loader = LoaderKind.Forge; loaderVersion = v; break;
                    case "net.neoforged": loader = LoaderKind.NeoForge; loaderVersion = v; break;
                }
            }
            // A complete component list with no loader component IS a statement: vanilla.
            return new Pack(mc, loader, loaderVersion, loader != LoaderKind.None);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
