using System.Globalization;
using System.IO;
using System.Text.Json;

namespace OriginLauncher.App.Core.Import.Sources;

// The CurseForge app: <root>/Instances/<name>/minecraftinstance.json, and the instance folder
// IS the game dir. From the json: name, gameVersion ("1.21.1"), baseModLoader (an object whose
// name is "<loader>-<version>": forge- / neoforge- / fabric- / quilt-; null or {} for vanilla
// and tolerated), lastPlayed (ISO; "0001-01-01T00:00:00" = never) and playedCount.
// timePlayed has no known unit, so it is NOT used as playtime: the picker only gets
// recency (and the launch count as tie info). No pin exists.
// The root is %USERPROFILE%\curseforge\minecraft (or the Documents one); a custom location is
// something only Browse can find. A root given as the Instances folder itself also works.
public sealed class CurseForgeSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.CurseForge;

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) =>
        Task.Run(() =>
        {
            if (string.IsNullOrWhiteSpace(roots.CurseForgeDir) || !Directory.Exists(roots.CurseForgeDir)) return;
            var instances = Path.Combine(roots.CurseForgeDir, "Instances");
            if (!Directory.Exists(instances)) instances = roots.CurseForgeDir;
            foreach (var folder in fs.EnumerateDirectories(instances).OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
            {
                ct.ThrowIfCancellationRequested();
                if (ReadInstance(folder, fs, ct) is { } instance) into.Instances.Add(instance);
            }
        }, ct);

    /// <summary>One instance folder (the one holding minecraftinstance.json), or null when it isn't one. Also used by Browse.</summary>
    public static DetectedInstance? ReadInstance(string instanceDir, SourceFs fs, CancellationToken ct)
    {
        var file = Path.Combine(instanceDir, "minecraftinstance.json");
        if (!File.Exists(file)) return null;

        var folderName = Path.GetFileName(Path.TrimEndingDirectorySeparator(instanceDir));
        var instance = new DetectedInstance { Kind = SourceKind.CurseForge, Name = folderName, GameDir = instanceDir };
        var text = fs.ReadText(file);
        if (text == null) return SourceHelpers.Unreadable(instance);

        try
        {
            using var doc = JsonDocument.Parse(text, new JsonDocumentOptions { AllowTrailingCommas = true });
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return InstanceInference.Complete(instance, fs, ct);

            if (Str(root, "name") is { Length: > 0 } name) instance = instance with { Name = name };

            var mc = Str(root, "gameVersion");
            var loader = LoaderKind.None;
            string? loaderVersion = null;
            var loaderKnown = false;
            if (root.TryGetProperty("baseModLoader", out var modLoader) && modLoader.ValueKind == JsonValueKind.Object)
            {
                mc ??= Str(modLoader, "minecraftVersion");
                if (Str(modLoader, "name") is { Length: > 0 } loaderName)
                    loaderKnown = ParseLoader(loaderName, out loader, out loaderVersion);
            }
            var vanilla = root.TryGetProperty("isVanilla", out var v) && v.ValueKind == JsonValueKind.True;

            if (root.TryGetProperty("lastPlayed", out var played) && played.ValueKind == JsonValueKind.String
                && DateTimeOffset.TryParse(played.GetString(), CultureInfo.InvariantCulture,
                    DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var when)
                && when.Year > 1)
                instance = instance with { LastPlayed = when };
            if (root.TryGetProperty("playedCount", out var count) && count.ValueKind == JsonValueKind.Number && count.TryGetInt32(out var launches))
                instance = instance with { LaunchCount = launches };

            if (mc == null) return InstanceInference.Complete(instance, fs, ct);
            instance = instance with { McVersion = mc };

            if (loaderKnown || vanilla)
                return instance with { Loader = loader, LoaderVersion = loaderVersion };

            // A modded instance whose loader isn't recorded: the log may say (then the loader is a guess).
            var sniffed = LatestLogSniffer.SniffGameDir(instanceDir, fs);
            return sniffed != null
                ? instance with { Loader = sniffed.Loader, LoaderVersion = sniffed.LoaderVersion, Confidence = Confidence.Inferred }
                : instance with { Confidence = Confidence.Inferred };
        }
        catch (JsonException)
        {
            return InstanceInference.Complete(instance, fs, ct);
        }
    }

    private static bool ParseLoader(string name, out LoaderKind loader, out string? version)
    {
        loader = LoaderKind.None;
        version = null;
        foreach (var (prefix, kind) in new[]
                 {
                     ("neoforge-", LoaderKind.NeoForge), ("forge-", LoaderKind.Forge),
                     ("fabric-", LoaderKind.Fabric), ("quilt-", LoaderKind.Quilt),
                 })
        {
            if (!name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) continue;
            loader = kind;
            version = name[prefix.Length..];
            return true;
        }
        return false;
    }

    private static string? Str(JsonElement obj, string name) =>
        obj.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
}
