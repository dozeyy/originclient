using System.IO;
using System.IO.Compression;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Compat;

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
        catch (Exception ex) when (ex is InvalidDataException or IOException or NotSupportedException
                                       or InvalidOperationException or KeyNotFoundException)
        {
            // (InvalidOperation / KeyNotFound: a metadata file shaped in a way the readers did not expect.)
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

            var provides = new List<string>();
            var providedVersions = new Dictionary<string, string>(StringComparer.Ordinal);
            ReadProvides(root, provides, providedVersions);

            var nested = new List<NestedMod>();
            var incomplete = false;
            if (readNested && root.TryGetProperty("jars", out var jars) && jars.ValueKind == JsonValueKind.Array)
            {
                foreach (var jarEntry in jars.EnumerateArray())
                {
                    if (jarEntry.ValueKind != JsonValueKind.Object || !jarEntry.TryGetProperty("file", out var file)
                        || file.ValueKind != JsonValueKind.String) continue;
                    var nestedIncomplete = depth >= MaxNestingDepth;
                    var nestedInfo = nestedIncomplete ? null : ReadNestedJar(zip, file.GetString() ?? "", depth + 1, budget, out nestedIncomplete);
                    incomplete |= nestedIncomplete || nestedInfo is { NestedIncomplete: true };
                    if (nestedInfo?.Id != null)
                        nested.Add(new NestedMod(nestedInfo.Id, nestedInfo.Version, nestedInfo.Name) { Provides = nestedInfo.Provides });
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
                Recommends = Dependencies(root, "recommends"),
                Conflicts = Dependencies(root, "conflicts"),
                Provides = provides,
                ProvidedVersions = providedVersions,
                Environment = ReadEnvironment(root),
                Nested = nested,
                NestedIncomplete = incomplete,
            };
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException)
        {
            return new ModInfo { FileName = fileName, Loader = ModLoader.Fabric }; // Id null = unreadable
        }
    }

    // A nested jar is opened from memory (ZipArchive needs a seekable stream); a
    // huge one is skipped — only its id is wanted, and nothing nests that deep.
    // incomplete = the jar could not be read although it is there (too big for the budget, damaged, lying about its
    // size), so ids inside it are unknown. A listed file that is absent, or a nested jar with no fabric.mod.json
    // (a plain library), is a definite "no mod here" and does not count.
    private static ModInfo? ReadNestedJar(ZipArchive zip, string path, int depth, Budget budget, out bool incomplete)
    {
        incomplete = false;
        var entry = zip.GetEntry(path);
        if (entry == null) return null;
        if (entry.Length > MaxNestedJarBytes || !budget.TryTake(entry.Length))
        {
            incomplete = true;
            return null;
        }
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
                    if (buffer.Length + n > entry.Length)
                    {
                        incomplete = true;
                        return null;
                    }
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
            incomplete = true;
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

    // mcmod.info: a JSON array of mods, or {"modList": [...]}. The first mod is the jar's identity; the others are
    // listed as provided ids (FML refuses two jars with the same mod id, whichever entry carries it).
    // "requiredMods" / "dependencies" are read as plain mod ids with no version: FML 1.8.9 only enforces the
    // @Mod(dependencies = ...) annotation inside the class files, never this file, so they are a hint, not a fact.
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
                var provides = new List<string>();
                for (var i = 1; i < root.GetArrayLength(); i++)
                    if (root[i].ValueKind == JsonValueKind.Object && Str(root[i], "modid") is { Length: > 0 } other) provides.Add(other);
                var depends = new List<ModDependency>();
                foreach (var key in new[] { "requiredMods", "dependencies" })
                    foreach (var raw in StringList(first, key))
                        if (LegacyDependencyId(raw) is { } id && depends.All(d => d.Id != id))
                            depends.Add(new ModDependency(id, new[] { "*" }));
                return new ModInfo
                {
                    FileName = fileName, Loader = ModLoader.LegacyForge,
                    Id = Str(first, "modid"), Version = Str(first, "version"),
                    Name = Str(first, "name"), McVersion = Str(first, "mcversion"),
                    Provides = provides, Depends = depends,
                };
            }
        }
        catch (JsonException)
        {
            // fall through
        }
        return new ModInfo { FileName = fileName, Loader = ModLoader.LegacyForge };
    }

    // "required-after:Foo@[1.0,)" / "before:bar" / "Foo" -> "Foo"; the platform itself ("Forge", "FML", "mcp") is no mod.
    private static string? LegacyDependencyId(string raw)
    {
        var text = raw.Trim();
        var colon = text.IndexOf(':');
        if (colon >= 0) text = text[(colon + 1)..];
        var at = text.IndexOf('@');
        if (at >= 0) text = text[..at];
        text = text.Trim();
        if (text.Length == 0 || text.Equals("Forge", StringComparison.OrdinalIgnoreCase)
            || text.Equals("FML", StringComparison.OrdinalIgnoreCase) || text.Equals("mcp", StringComparison.OrdinalIgnoreCase)
            || text.Equals("minecraft", StringComparison.OrdinalIgnoreCase)) return null;
        return text;
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

    // "provides": ["a", {"id": "b", "version": "1.2"}]
    private static void ReadProvides(JsonElement root, List<string> ids, Dictionary<string, string> versions)
    {
        if (!root.TryGetProperty("provides", out var v) || v.ValueKind != JsonValueKind.Array) return;
        foreach (var item in v.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.String) ids.Add(item.GetString()!);
            else if (item.ValueKind == JsonValueKind.Object && Str(item, "id") is { Length: > 0 } id)
            {
                ids.Add(id);
                if (Str(item, "version") is { Length: > 0 } version) versions[id] = version;
            }
        }
    }

    private static ModEnvironment ReadEnvironment(JsonElement root) => Str(root, "environment") switch
    {
        "client" => ModEnvironment.Client,
        "server" => ModEnvironment.Server,
        _ => ModEnvironment.Both,
    };

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
