using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Import.Mods;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.Tests.Import;

// Shared fixtures for the import tests. Every file is generated in code in a temp
// folder: jars are zips with a fabric.mod.json, launchers are JSON/INI written here.
// Nothing here ever looks at the real %LocalAppData%\OriginLauncher or any real launcher.
internal static class Kit
{
    public static string Temp(string name) => Directory.CreateTempSubdirectory("import-" + name + "-").FullName;

    public static string Write(string root, string relative, string text, DateTime? mtime = null)
    {
        var path = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        if (mtime != null) File.SetLastWriteTimeUtc(path, mtime.Value);
        return path;
    }

    public static string WriteBytes(string root, string relative, byte[] bytes)
    {
        var path = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    /// <summary>A real (uncompressed NBT) servers.dat, as Minecraft writes it: root { servers: [ { name, ip }, ... ] }.</summary>
    public static byte[] ServersDat(params (string Name, string Ip)[] servers)
    {
        using var ms = new MemoryStream();
        using (var w = new BinaryWriter(ms, Encoding.UTF8, leaveOpen: true))
        {
            void Str(string s)
            {
                var b = Encoding.UTF8.GetBytes(s);
                w.Write((byte)(b.Length >> 8));
                w.Write((byte)b.Length);
                w.Write(b);
            }
            void Int(int v) { w.Write((byte)(v >> 24)); w.Write((byte)(v >> 16)); w.Write((byte)(v >> 8)); w.Write((byte)v); }
            w.Write((byte)10); Str("");
            w.Write((byte)9); Str("servers"); w.Write((byte)10); Int(servers.Length);
            foreach (var (name, ip) in servers)
            {
                w.Write((byte)8); Str("name"); Str(name);
                w.Write((byte)8); Str("ip"); Str(ip);
                w.Write((byte)0);
            }
            w.Write((byte)0);
        }
        return ms.ToArray();
    }

    /// <summary>The (name, ip) pairs of a servers.dat, in order (null when it can't be read).</summary>
    public static List<(string Name, string Ip)>? Servers(string path) =>
        ServerList.Read(File.Exists(path) ? File.ReadAllBytes(path) : null)?.Select(e => (e.Name, e.Address)).ToList();

    public static string Read(string root, string relative) =>
        File.ReadAllText(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));

    public static bool Exists(string root, string relative)
    {
        var path = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
        return File.Exists(path) || Directory.Exists(path);
    }

    // ---- zips / jars ----

    public static byte[] Zip(params (string Name, byte[] Data)[] entries)
    {
        using var ms = new MemoryStream();
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
            foreach (var (name, data) in entries)
            {
                var entry = zip.CreateEntry(name);
                // A fixed stamp: the same entries always make the same bytes (a "now" stamp made two identical
                // packs differ whenever the fixture crossed a 2-second boundary, which flaked the dedupe tests).
                entry.LastWriteTime = new DateTimeOffset(2020, 1, 1, 0, 0, 0, TimeSpan.Zero);
                using var s = entry.Open();
                s.Write(data);
            }
        return ms.ToArray();
    }

    public static (string, byte[]) Text(string name, string text) => (name, Encoding.UTF8.GetBytes(text));

    public static byte[] FabricJar(string id, string version = "1.0.0", object? minecraft = null,
        Dictionary<string, object>? depends = null, Dictionary<string, object>? breaks = null,
        string[]? provides = null, string? name = null, params (string File, byte[] Jar)[] nested)
    {
        var dep = new Dictionary<string, object>();
        if (minecraft != null) dep["minecraft"] = minecraft;
        if (depends != null) foreach (var kv in depends) dep[kv.Key] = kv.Value;
        var json = new Dictionary<string, object>
        {
            ["schemaVersion"] = 1, ["id"] = id, ["version"] = version, ["name"] = name ?? id,
            ["depends"] = dep,
        };
        if (breaks != null) json["breaks"] = breaks;
        if (provides != null) json["provides"] = provides;
        if (nested.Length > 0) json["jars"] = nested.Select(n => new Dictionary<string, string> { ["file"] = n.File }).ToArray();

        var entries = new List<(string, byte[])> { Text("fabric.mod.json", JsonSerializer.Serialize(json)) };
        entries.AddRange(nested.Select(n => (n.File, n.Jar)));
        return Zip(entries.ToArray());
    }

    /// <summary>A fabricated originclient jar with Sodium / Iris / Mod Menu nested jar-in-jar (like the real 1.21.1 build).</summary>
    public static byte[] OriginClientJar() => FabricJar("originclient", "0.4.2", name: "Origin Client",
        nested: new[]
        {
            ("META-INF/jars/sodium.jar", FabricJar("sodium", "0.6.13+mc1.21.1", name: "Sodium")),
            ("META-INF/jars/iris.jar", FabricJar("iris", "1.8.8+mc1.21.1", name: "Iris")),
            ("META-INF/jars/modmenu.jar", FabricJar("modmenu", "11.0.3", name: "Mod Menu")),
        });

    // ---- hashing ----

    /// <summary>A hash of a whole folder: file list, content and (optionally) modification times.</summary>
    public static string TreeHash(string root, bool mtimes = false)
    {
        if (!Directory.Exists(root)) return "(missing)";
        var sb = new StringBuilder();
        foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
                     .OrderBy(f => f, StringComparer.Ordinal))
        {
            sb.Append(Path.GetRelativePath(root, file).Replace('\\', '/')).Append('|')
              .Append(Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(file))));
            if (mtimes) sb.Append('|').Append(File.GetLastWriteTimeUtc(file).Ticks);
            sb.AppendLine();
        }
        foreach (var dir in Directory.EnumerateDirectories(root, "*", SearchOption.AllDirectories)
                     .OrderBy(f => f, StringComparer.Ordinal))
            sb.Append("dir:").AppendLine(Path.GetRelativePath(root, dir).Replace('\\', '/'));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString())));
    }
}

/// <summary>A synchronous IProgress (Progress&lt;T&gt; posts asynchronously, which can't cancel "right here").</summary>
internal sealed class SyncProgress : IProgress<ImportProgress>
{
    private readonly Action<ImportProgress> _on;
    public List<ImportProgress> Seen { get; } = new();
    public SyncProgress(Action<ImportProgress>? on = null) => _on = on ?? (_ => { });
    public void Report(ImportProgress value)
    {
        Seen.Add(value);
        _on(value);
    }
}

/// <summary>The launcher settings the engine edits through IImportSettings: a plain json file in a temp folder.</summary>
internal sealed class FakeSettings : IImportSettings
{
    private readonly string _path;

    public FakeSettings(string path, string selectedVersion = "1.21.11", string? selectedProfile = null)
    {
        _path = path;
        Save(new State { SelectedVersion = selectedVersion, SelectedProfileId = selectedProfile });
    }

    public sealed class State
    {
        public string? SelectedVersion { get; set; }
        public string? SelectedProfileId { get; set; }
        public List<ModProfile> Profiles { get; set; } = new();
    }

    public State Load() => JsonSerializer.Deserialize<State>(File.ReadAllText(_path))!;
    private void Save(State s) => File.WriteAllText(_path, JsonSerializer.Serialize(s));
    public string FileHash() => Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(_path)));

    public int ApplyCalls { get; private set; }

    // Locked like SettingsStore.Update (the real store serializes writers): concurrent-import tests share one fake.
    private readonly object _gate = new();

    public void Apply(SettingsDelta delta) { lock (_gate) ApplyLocked(delta); }

    private void ApplyLocked(SettingsDelta delta)
    {
        ApplyCalls++;
        var s = Load();
        if (delta.AddProfile != null) s.Profiles.Add(delta.AddProfile);
        foreach (var profile in delta.AddProfiles ?? new List<ModProfile>())
            if (s.Profiles.All(p => p.Id != profile.Id)) s.Profiles.Add(profile);
        if (delta.SetSelectedVersion != null) s.SelectedVersion = delta.SetSelectedVersion;
        if (delta.SetSelectedProfileId != null) s.SelectedProfileId = delta.SetSelectedProfileId;
        Save(s);
    }

    public void Revert(SettingsDelta delta) { lock (_gate) RevertLocked(delta); }

    private void RevertLocked(SettingsDelta delta)
    {
        var s = Load();
        if (delta.AddProfile != null) s.Profiles.RemoveAll(p => p.Id == delta.AddProfile.Id);
        foreach (var profile in delta.AddProfiles ?? new List<ModProfile>())
            s.Profiles.RemoveAll(p => p.Id == profile.Id);
        if (delta.SetSelectedVersion != null) s.SelectedVersion = delta.PreviousSelectedVersion;
        if (delta.SetSelectedProfileId != null) s.SelectedProfileId = delta.PreviousSelectedProfileId;
        Save(s);
    }
}

/// <summary>A temp world for engine tests: a source game dir, the Origin instances/ + imports/ folders, an engine to run.</summary>
internal sealed class Rig : IDisposable
{
    public const string Account = "069a79f444e94726a5befca90e38aaf5";

    public string Root { get; } = Kit.Temp("rig");
    public string Source => Path.Combine(Root, "src-instance");
    public string Instances => Path.Combine(Root, "OriginLauncher", "instances");
    public string Imports => Path.Combine(Root, "OriginLauncher", "imports");
    public string BundledJar => Path.Combine(Root, "bundled", "originclient-1.21.1.jar");
    public FakeSettings Settings { get; }
    public List<string> Opened { get; } = new();
    public HashSet<string> RunningDirs { get; } = new();
    public List<string> LogLines { get; } = new();

    public Rig()
    {
        Directory.CreateDirectory(Source);
        Directory.CreateDirectory(Path.GetDirectoryName(BundledJar)!);
        File.WriteAllBytes(BundledJar, Kit.OriginClientJar());
        Settings = new FakeSettings(Path.Combine(Root, "settings.json"));
    }

    public ImportEnvironment Env(Func<string, bool>? isRunning = null, Func<string, long>? freeSpace = null) => new()
    {
        InstancesRoot = Instances,
        ImportsRoot = Imports,
        FreeSpace = freeSpace ?? (_ => long.MaxValue),
        IsGameRunning = isRunning ?? (dir => RunningDirs.Contains(dir)),
        OnSourceOpen = Opened.Add,
        Log = LogLines.Add,
        IsOriginProvisioned = IsOriginJar,
    };

    /// <summary>The jar names (file name, ".disabled" or not) the tests treat as Origin's own (ModManager.IsOriginProvisioned).</summary>
    public HashSet<string> OriginJars { get; } = new(StringComparer.OrdinalIgnoreCase);

    public bool IsOriginJar(string name) => OriginJars.Contains(SharedModsLedger.FileKey(name));

    /// <summary>Puts one of Origin's own jars in the version's shared mods folder (what a profile launch loads beside the profile).</summary>
    public string OriginJar(string version, string fileName, byte[] bytes)
    {
        OriginJars.Add(SharedModsLedger.FileKey(fileName));
        var dir = InstanceLayout.ModsDir(Instances, version);
        Directory.CreateDirectory(dir);
        var path = Path.Combine(dir, fileName);
        File.WriteAllBytes(path, bytes);
        return path;
    }

    public ImportEngine Engine(Func<string, bool>? isRunning = null, Func<string, long>? freeSpace = null) => new(Env(isRunning, freeSpace));

    public static readonly string[] Supported =
    {
        "1.21.11", "1.21.4", "1.21.1", "1.20.4", "1.16.5", "1.8.9", "26.3",
    };

    public DetectedInstance SourceInstance(string mc = "1.21.1", LoaderKind loader = LoaderKind.Fabric, string name = "Hypixel") => new()
    {
        Kind = SourceKind.Folder, Name = name, GameDir = Source, McVersion = mc, Loader = loader,
        Confidence = Confidence.Exact,
    };

    public ImportRequest Request(string target = "1.21.1", ImportOptions? options = null, string? account = Account,
        bool playWithExternalMods = true, string mc = "1.21.1") => new()
    {
        Source = SourceInstance(mc),
        TargetVersion = target,
        AccountKey = account,
        Options = options ?? new ImportOptions(),
        SupportedTargets = Supported,
        BundledOriginJarPath = target == "1.21.1" ? BundledJar : null,
        CatalogMods = target == "1.21.1" ? Array.Empty<CatalogMod>() : new[]
        {
            new CatalogMod("sodium", "sodium-fabric-0.8.12+mc1.21.11.jar"),
            new CatalogMod("iris", "iris-fabric-1.10.7+mc1.21.11.jar"),
            new CatalogMod("modmenu", "modmenu-17.0.0.jar"),
        },
        PlayWithExternalMods = playWithExternalMods,
        // What the launcher's own request carries (ImportBackend.BuildRequest): the settings as they are now.
        SelectedVersion = Settings.Load().SelectedVersion,
        SelectedProfileId = Settings.Load().SelectedProfileId,
        ExistingProfiles = Settings.Load().Profiles,
    };

    public string GameDir(string version = "1.21.1") => Path.Combine(Instances, version, "accounts", Account);

    public void Dispose()
    {
        try { Directory.Delete(Root, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}

/// <summary>A plan step (a destination-side Custom op) that fails with the given exception: a hard failure, so the run rolls back.</summary>
internal sealed class FailingStep(Func<Exception> failure) : IImportPlanContributor
{
    public Task ContributeAsync(ImportPlan plan, ImportRequest request, SourceFs fs, CancellationToken ct)
    {
        plan.Ops.Add(new PlannedOp
        {
            Kind = OpKind.Custom, Item = PlanItemKind.Cache, Label = "caches", Dest = plan.TargetGameDir,
            Run = _ => throw failure(),
        });
        return Task.CompletedTask;
    }
}
