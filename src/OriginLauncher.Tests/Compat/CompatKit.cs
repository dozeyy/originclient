using System.IO.Compression;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Compat;

namespace OriginLauncher.Tests.Compat;

// Builders for the compatibility tests. Descriptors are made directly (no jar) for the engine tests, which is what
// keeps 300-mod sets cheap; the fixture tests build real jars from metadata copied out of real mods.
internal static class CompatKit
{
    public static readonly CompatPlatform Fabric121 = new("1.21.1", LoaderFamily.Fabric, "0.16.14", "21");
    public static readonly CompatPlatform Forge189 = new("1.8.9", LoaderFamily.LegacyForge, null, "8");

    public static ModDependency Dep(string id, params string[] ranges) =>
        new(id, ranges.Length == 0 ? new[] { "*" } : ranges);

    public static ModInfo Info(
        string id, string version = "1.0.0", string? name = null, string? mc = null,
        (string Id, string Range)[]? needs = null, (string Id, string Range)[]? breaks = null,
        (string Id, string Range)[]? conflicts = null, (string Id, string Range)[]? recommends = null,
        string[]? provides = null, NestedMod[]? nested = null, ModEnvironment env = ModEnvironment.Both,
        ModLoader loader = ModLoader.Fabric, string? fileName = null)
    {
        var depends = new List<ModDependency>();
        if (mc != null) depends.Add(Dep("minecraft", mc));
        foreach (var (depId, range) in needs ?? Array.Empty<(string, string)>()) depends.Add(Dep(depId, range));
        return new ModInfo
        {
            FileName = fileName ?? $"{id}-{version}.jar",
            Loader = loader,
            Id = id,
            Version = version,
            Name = name ?? id,
            Depends = depends,
            Breaks = (breaks ?? Array.Empty<(string, string)>()).Select(b => Dep(b.Id, b.Range)).ToList(),
            Conflicts = (conflicts ?? Array.Empty<(string, string)>()).Select(b => Dep(b.Id, b.Range)).ToList(),
            Recommends = (recommends ?? Array.Empty<(string, string)>()).Select(b => Dep(b.Id, b.Range)).ToList(),
            Provides = provides ?? Array.Empty<string>(),
            Nested = nested ?? Array.Empty<NestedMod>(),
            Environment = env,
        };
    }

    public static ModFile File(ModInfo info, ModRole role = ModRole.Profile, bool enabled = true, string? fileName = null)
    {
        var name = fileName ?? info.FileName;
        return new ModFile("/mods/" + name + (enabled ? "" : ".disabled"), enabled ? name : name + ".disabled", enabled, role, info);
    }

    public static ModFile Mod(string id, string version = "1.0.0", ModRole role = ModRole.Profile, bool enabled = true,
        (string Id, string Range)[]? needs = null, (string Id, string Range)[]? breaks = null, string? mc = null,
        string[]? provides = null, NestedMod[]? nested = null) =>
        File(Info(id, version, needs: needs, breaks: breaks, mc: mc, provides: provides, nested: nested), role, enabled);

    public static CompatReport Check(params ModFile[] mods) => CompatEngine.Check(mods, Fabric121);

    public static CompatReport Check(CompatPlatform platform, params ModFile[] mods) => CompatEngine.Check(mods, platform);

    public static IEnumerable<CompatFinding> Of(CompatReport report, FindingKind kind) =>
        report.Findings.Where(f => f.Kind == kind);

    // ---- real jars ----

    public static byte[] Zip(params (string Name, byte[] Data)[] entries)
    {
        using var ms = new MemoryStream();
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
            foreach (var (name, data) in entries)
            {
                var entry = zip.CreateEntry(name);
                using var s = entry.Open();
                s.Write(data);
            }
        return ms.ToArray();
    }

    public static string FixtureDir => Path.Combine(AppContext.BaseDirectory, "Compat", "Fixtures");

    /// <summary>A jar made from a fixture: the mod's real fabric.mod.json plus its nested jars' real fabric.mod.json files.</summary>
    public static byte[] FixtureJar(string fixtureFile)
    {
        using var doc = JsonDocument.Parse(System.IO.File.ReadAllText(Path.Combine(FixtureDir, fixtureFile)));
        var entries = new List<(string, byte[])>
        {
            ("fabric.mod.json", Encoding.UTF8.GetBytes(doc.RootElement.GetProperty("meta").GetRawText())),
        };
        foreach (var nested in doc.RootElement.GetProperty("nested").EnumerateArray())
        {
            var inner = Zip(("fabric.mod.json", Encoding.UTF8.GetBytes(nested.GetProperty("meta").GetRawText())));
            entries.Add((nested.GetProperty("file").GetString()!, inner));
        }
        return Zip(entries.ToArray());
    }

    /// <summary>The fixture read the way the launcher reads a jar (ModMetadataReader), as an entry.</summary>
    public static ModFile FixtureMod(string fixtureFile, ModRole role = ModRole.Profile, bool enabled = true)
    {
        var jarName = fixtureFile.Replace(".json", ".jar");
        using var stream = new MemoryStream(FixtureJar(fixtureFile));
        var info = ModMetadataReader.Read(stream, jarName);
        return File(info, role, enabled, jarName);
    }
}
