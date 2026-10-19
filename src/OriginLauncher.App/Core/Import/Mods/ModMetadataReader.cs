using System.IO;
using System.IO.Compression;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Import.Mods;

public enum ModLoader { Unknown, Fabric, Quilt, Forge, NeoForge, LegacyForge }

/// <summary>One "depends" / "breaks" entry: a mod id and the version requirements (any one may hold).</summary>
public sealed record ModDependency(string Id, IReadOnlyList<string> Ranges)
{
    public VersionPredicate Predicate => VersionPredicate.Parse(Ranges);
}

/// <summary>A mod jar nested inside another (jar-in-jar): its own id, version and name.</summary>
public sealed record NestedMod(string Id, string? Version, string? Name);

/// <summary>What a mod jar says about itself. Identity comes from metadata, never from the file name.</summary>
public sealed record ModInfo
{
    public required string FileName { get; init; }
    public ModLoader Loader { get; init; }
    public string? Id { get; init; }
    public string? Version { get; init; }
    public string? Name { get; init; }

    /// <summary>mcmod.info's "mcversion" (legacy Forge only).</summary>
    public string? McVersion { get; init; }
    public IReadOnlyList<ModDependency> Depends { get; init; } = Array.Empty<ModDependency>();
    public IReadOnlyList<ModDependency> Breaks { get; init; } = Array.Empty<ModDependency>();
    public IReadOnlyList<string> Provides { get; init; } = Array.Empty<string>();
    public IReadOnlyList<NestedMod> Nested { get; init; } = Array.Empty<NestedMod>();

    /// <summary>The file is not a readable zip/jar at all.</summary>
    public bool ReadFailed { get; init; }

    /// <summary>The requirement on Minecraft ("depends": {"minecraft": ...}), or null when the mod names none.</summary>
    public VersionPredicate? MinecraftRange =>
        Depends.FirstOrDefault(d => d.Id == "minecraft") is { } d ? d.Predicate : null;
}

// Reads the small metadata file(s) of a mod jar — fabric.mod.json, quilt.mod.json,
// META-INF/mods.toml, META-INF/neoforge.mods.toml, mcmod.info — and nothing else.
// A jar that carries fabric.mod.json counts as Fabric even when it also carries
// Forge or Quilt files (multi-loader jars run on Fabric). Everything is
// best-effort: a corrupt jar or a broken JSON gives a ModInfo that says so, and
// never throws.
public static class ModMetadataReader
{
    private const int MaxMetadataBytes = 2 * 1024 * 1024;
    private const long MaxNestedJarBytes = 64L * 1024 * 1024;

    // A jar inside a jar inside a jar is a real thing (a bundle of bundles) but never deeper than this; and
    // everything buffered while reading ONE top-level jar is capped, so a hostile jar that nests itself (a
    // zip quine) or declares huge entries can't exhaust memory or the stack while a plan is being made.
    private const int MaxNestingDepth = 2;
    public const long DefaultNestedBudget = 128L * 1024 * 1024;

    private sealed class Budget
    {
        private long _remaining;
        public Budget(long bytes) => _remaining = bytes;

        public bool TryTake(long bytes)
        {
            if (bytes > _remaining) return false;
            _remaining -= bytes;
            return true;
        }
    }

    private static readonly JsonDocumentOptions Lenient = new()
    {
        AllowTrailingCommas = true,
        CommentHandling = JsonCommentHandling.Skip,
    };

    /// <summary>Reads the jar in <paramref name="jar"/>. <paramref name="readNested"/> also reads jar-in-jar entries one level deep.</summary>
    public static ModInfo Read(Stream jar, string fileName, bool readNested = true, long nestedByteBudget = DefaultNestedBudget)
    {
        try
        {
            using var zip = new ZipArchive(jar, ZipArchiveMode.Read, leaveOpen: true);
            return ReadZip(zip, fileName, readNested, new Budget(nestedByteBudget));
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException or NotSupportedException)
        {
            return new ModInfo { FileName = fileName, ReadFailed = true };
        }
    }

    private static ModInfo ReadZip(ZipArchive zip, string fileName, bool readNested, Budget budget)
    {
        var fabric = ReadText(zip, "fabric.mod.json");
        if (fabric != null) return ReadFabric(zip, fileName, fabric, readNested, depth: 0, budget);

        var quilt = ReadText(zip, "quilt.mod.json");
        if (quilt != null) return ReadQuilt(fileName, quilt);

        var neo = ReadText(zip, "META-INF/neoforge.mods.toml");
        if (neo != null) return ReadToml(fileName, neo, ModLoader.NeoForge);

        var forge = ReadText(zip, "META-INF/mods.toml");
        if (forge != null) return ReadToml(fileName, forge, ModLoader.Forge);

        var legacy = ReadText(zip, "mcmod.info");
        if (legacy != null) return ReadLegacy(fileName, legacy);

        return new ModInfo { FileName = fileName, Loader = ModLoader.Unknown };
    }

    private static ModInfo ReadFabric(ZipArchive zip, string fileName, string json, bool readNested, int depth, Budget budget)
    {
        try
        {
            using var doc = JsonDocument.Parse(json, Lenient);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return new ModInfo { FileName = fileName, Loader = ModLoader.Fabric };

            var nested = new List<NestedMod>();
            if (readNested && root.TryGetProperty("jars", out var jars) && jars.ValueKind == JsonValueKind.Array)
            {
                foreach (var jarEntry in jars.EnumerateArray())
                {
                    if (jarEntry.ValueKind != JsonValueKind.Object || !jarEntry.TryGetProperty("file", out var file)
                        || file.ValueKind != JsonValueKind.String) continue;
                    var nestedInfo = depth >= MaxNestingDepth ? null : ReadNestedJar(zip, file.GetString()!, depth + 1, budget);
                    if (nestedInfo?.Id != null) nested.Add(new NestedMod(nestedInfo.Id, nestedInfo.Version, nestedInfo.Name));
                    // Jar-in-jar of jar-in-jar (Fabric API's modules inside a bundle): count their ids too.
                    if (nestedInfo != null) nested.AddRange(nestedInfo.Nested);
                }
            }

            return new ModInfo
            {
                FileName = fileName,
                Loader = ModLoader.Fabric,
                Id = Str(root, "id"),
                Version = Str(root, "version"),
                Name = Str(root, "name"),
                Depends = Dependencies(root, "depends"),
                Breaks = Dependencies(root, "breaks"),
                Provides = StringList(root, "provides"),
                Nested = nested,
            };
        }
        catch (JsonException)
        {
            return new ModInfo { FileName = fileName, Loader = ModLoader.Fabric }; // Id null = unreadable
        }
    }

    // A nested jar is opened from memory (ZipArchive needs a seekable stream); a
    // huge one is skipped — only its id is wanted, and nothing nests that deep.
    private static ModInfo? ReadNestedJar(ZipArchive zip, string path, int depth, Budget budget)
    {
        var entry = zip.GetEntry(path);
        if (entry == null || entry.Length > MaxNestedJarBytes || !budget.TryTake(entry.Length)) return null;
        try
        {
            using var buffer = new MemoryStream((int)entry.Length);
            using (var source = entry.Open())
            {
                // A zip can lie about its size: never inflate more than the entry says it holds.
                var block = new byte[81920];
                int n;
                while ((n = source.Read(block, 0, block.Length)) > 0)
                {
                    if (buffer.Length + n > entry.Length) return null;
                    buffer.Write(block, 0, n);
                }
            }
            buffer.Position = 0;
            using var inner = new ZipArchive(buffer, ZipArchiveMode.Read);
            var text = ReadText(inner, "fabric.mod.json");
            if (text == null) return null;
            // Reads the nested jar's own jars too (a bundle of bundles); real jars nest two deep at most.
            return ReadFabric(inner, Path.GetFileName(path), text, readNested: true, depth, budget);
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException or NotSupportedException)
        {
            return null;
        }
    }

    private static ModInfo ReadQuilt(string fileName, string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json, Lenient);
            if (doc.RootElement.ValueKind == JsonValueKind.Object
                && doc.RootElement.TryGetProperty("quilt_loader", out var loader) && loader.ValueKind == JsonValueKind.Object)
            {
                string? name = null;
                if (loader.TryGetProperty("metadata", out var meta) && meta.ValueKind == JsonValueKind.Object)
                    name = Str(meta, "name");
                return new ModInfo
                {
                    FileName = fileName, Loader = ModLoader.Quilt,
                    Id = Str(loader, "id"), Version = Str(loader, "version"), Name = name,
                };
            }
        }
        catch (JsonException)
        {
            // fall through: still a Quilt-only jar
        }
        return new ModInfo { FileName = fileName, Loader = ModLoader.Quilt };
    }

    private static readonly Regex TomlModId = new(@"(?m)^\s*modId\s*=\s*""([^""]+)""", RegexOptions.CultureInvariant);
    private static readonly Regex TomlVersion = new(@"(?m)^\s*version\s*=\s*""([^""]*)""", RegexOptions.CultureInvariant);
    private static readonly Regex TomlDisplayName = new(@"(?m)^\s*displayName\s*=\s*""([^""]*)""", RegexOptions.CultureInvariant);

    // Forge / NeoForge mods.toml: only the first [[mods]] block's modId / version / displayName.
    private static ModInfo ReadToml(string fileName, string toml, ModLoader loader)
    {
        var start = toml.IndexOf("[[mods]]", StringComparison.Ordinal);
        var block = start >= 0 ? toml[start..] : toml;
        var id = TomlModId.Match(block);
        var version = TomlVersion.Match(block);
        var name = TomlDisplayName.Match(block);
        return new ModInfo
        {
            FileName = fileName, Loader = loader,
            Id = id.Success ? id.Groups[1].Value : null,
            Version = version.Success ? version.Groups[1].Value : null,
            Name = name.Success ? name.Groups[1].Value : null,
        };
    }

    // mcmod.info: a JSON array of mods, or {"modList": [...]}.
    private static ModInfo ReadLegacy(string fileName, string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json, Lenient);
            var root = doc.RootElement;
            if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("modList", out var list)) root = list;
            if (root.ValueKind == JsonValueKind.Array && root.GetArrayLength() > 0 && root[0].ValueKind == JsonValueKind.Object)
            {
                var first = root[0];
                return new ModInfo
                {
                    FileName = fileName, Loader = ModLoader.LegacyForge,
                    Id = Str(first, "modid"), Version = Str(first, "version"),
                    Name = Str(first, "name"), McVersion = Str(first, "mcversion"),
                };
            }
        }
        catch (JsonException)
        {
            // fall through
        }
        return new ModInfo { FileName = fileName, Loader = ModLoader.LegacyForge };
    }

    private static string? ReadText(ZipArchive zip, string path)
    {
        var entry = zip.GetEntry(path);
        if (entry == null || entry.Length > MaxMetadataBytes) return null;
        try
        {
            using var reader = new StreamReader(entry.Open(), System.Text.Encoding.UTF8, true);
            var text = reader.ReadToEnd();
            return text.Length > 0 && text[0] == '﻿' ? text[1..] : text;
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException)
        {
            return null;
        }
    }

    private static string? Str(JsonElement obj, string name) =>
        obj.ValueKind == JsonValueKind.Object && obj.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() : null;

    private static List<string> StringList(JsonElement obj, string name)
    {
        var list = new List<string>();
        if (obj.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Array)
            foreach (var item in v.EnumerateArray())
                if (item.ValueKind == JsonValueKind.String) list.Add(item.GetString()!);
        return list;
    }

    // "depends": { "id": "range" | ["range", ...] }
    private static List<ModDependency> Dependencies(JsonElement obj, string name)
    {
        var list = new List<ModDependency>();
        if (!obj.TryGetProperty(name, out var v) || v.ValueKind != JsonValueKind.Object) return list;
        foreach (var property in v.EnumerateObject())
        {
            var ranges = new List<string>();
            if (property.Value.ValueKind == JsonValueKind.String) ranges.Add(property.Value.GetString()!);
            else if (property.Value.ValueKind == JsonValueKind.Array)
                foreach (var item in property.Value.EnumerateArray())
                    if (item.ValueKind == JsonValueKind.String) ranges.Add(item.GetString()!);
            if (ranges.Count == 0) ranges.Add("*");
            list.Add(new ModDependency(property.Name, ranges));
        }
        return list;
    }
}
