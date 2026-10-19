using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.GameFiles;
using Xunit;

namespace OriginLauncher.Tests.Import;

/// <summary>A fact that only runs on Windows: it depends on FileShare.None conflicting between two handles in one process.</summary>
public sealed class WindowsOnlyFactAttribute : FactAttribute
{
    public WindowsOnlyFactAttribute()
    {
        if (!OperatingSystem.IsWindows())
            Skip = "Lock probing is verified on Windows only: it relies on FileShare.None conflicting between two handles, which the Unix advisory-lock emulation does not guarantee here.";
    }
}

internal static class EngineBKit
{
    public static ImportEngine Engine(Rig rig, IReadOnlyList<IImportPlanContributor> contributors, Func<string, long>? freeSpace = null,
        Func<string, bool>? isRunning = null, IVersionGate? versionGate = null) => new(new ImportEnvironment
        {
            InstancesRoot = rig.Instances, ImportsRoot = rig.Imports,
            FreeSpace = freeSpace ?? (_ => long.MaxValue),
            IsGameRunning = isRunning ?? (dir => rig.RunningDirs.Contains(dir)),
            OnSourceOpen = rig.Opened.Add, Log = rig.LogLines.Add, Contributors = contributors, VersionGate = versionGate,
        });

    public static string Sha1(byte[] data) => Convert.ToHexString(SHA1.HashData(data)).ToLowerInvariant();

    // ---- level.dat (gzip NBT) ----

    public static byte[] LevelDat(string name, long seed, bool modern = true)
    {
        using var ms = new MemoryStream();
        using (var gzip = new GZipStream(ms, CompressionLevel.Optimal, leaveOpen: true))
        {
            var w = new BinaryWriter(gzip);
            void Header(byte tag, string label) { w.Write(tag); Str(w, label); }
            Header(10, ""); // root
            Header(10, "Data");
            Header(1, "hardcore"); w.Write((byte)0);
            Header(8, "LevelName"); Str(w, name);
            Header(3, "version"); Int(w, 19133);
            Header(10, "Version"); Header(3, "Id"); Int(w, 3955); Header(8, "Name"); Str(w, "1.21.1"); w.Write((byte)0);
            Header(9, "ServerBrands"); w.Write((byte)8); Int(w, 1); Str(w, "vanilla");
            Header(12, "Padding"); Int(w, 2); Long(w, 1); Long(w, 2);
            if (modern)
            {
                Header(10, "WorldGenSettings"); Header(1, "bonus_chest"); w.Write((byte)0);
                Header(4, "seed"); Long(w, seed);
                Header(10, "dimensions"); w.Write((byte)0);
                w.Write((byte)0);
            }
            else
            {
                Header(4, "RandomSeed"); Long(w, seed);
            }
            w.Write((byte)0); // end Data
            w.Write((byte)0); // end root
            w.Flush();
        }
        return ms.ToArray();
    }

    private static void Str(BinaryWriter w, string s)
    {
        var bytes = Encoding.UTF8.GetBytes(s);
        w.Write((byte)(bytes.Length >> 8)); w.Write((byte)bytes.Length); w.Write(bytes);
    }

    private static void Int(BinaryWriter w, int v) { for (var i = 3; i >= 0; i--) w.Write((byte)(v >> (8 * i))); }
    private static void Long(BinaryWriter w, long v) { for (var i = 7; i >= 0; i--) w.Write((byte)(v >> (8 * i))); }

    /// <summary>A file that starts like a real WAL-mode SQLite database (the 100-byte header), then <paramref name="body"/>.</summary>
    public static byte[] Sqlite(string body)
    {
        var data = new byte[100 + body.Length];
        Encoding.ASCII.GetBytes("SQLite format 3\0").CopyTo(data, 0);
        data[18] = 2; data[19] = 2;
        Encoding.ASCII.GetBytes(body).CopyTo(data, 100);
        return data;
    }
}

/// <summary>Records every request; serves documents by URL; a URL set to null (or absent) is unreachable.</summary>
internal sealed class RecordingHttp : IMetadataHttp
{
    public Dictionary<string, byte[]?> Docs { get; } = new();
    public List<(string Url, int Length)> Requests { get; } = new();

    public Dictionary<string, string> FinalUrls { get; } = new();

    public Task<MetadataResponse?> GetAsync(string url, long maxBytes, CancellationToken ct)
    {
        Docs.TryGetValue(url, out var body);
        Requests.Add((url, body?.Length ?? 0));
        return Task.FromResult(body == null ? null : new MetadataResponse(body, FinalUrls.TryGetValue(url, out var final) ? final : url));
    }
}

/// <summary>An injectable gate with real mutual exclusion, so a test can see a second acquire wait.</summary>
internal sealed class FakeGate : IVersionGate
{
    private readonly SemaphoreSlim _lock = new(1, 1);
    public int Acquired;
    public int Released;

    public async Task<IAsyncDisposable> AcquireAsync(string version, CancellationToken ct)
    {
        await _lock.WaitAsync(ct);
        Interlocked.Increment(ref Acquired);
        return new Lease(this);
    }

    private sealed class Lease : IAsyncDisposable
    {
        private readonly FakeGate _gate;
        public Lease(FakeGate gate) => _gate = gate;
        public ValueTask DisposeAsync()
        {
            Interlocked.Increment(ref _gate.Released);
            _gate._lock.Release();
            return ValueTask.CompletedTask;
        }
    }
}

/// <summary>
/// A miniature Mojang world for version 1.21.1: the metadata documents (served by URL, with real SHA-1s) and
/// the matching game files, which the Write* methods lay out the way each launcher keeps them.
/// </summary>
internal sealed class Mojang
{
    public const string Version = "1.21.1";
    public const string Component = "java-runtime-delta";

    private const string VersionJsonUrl = "https://piston-meta.mojang.com/v1/packages/aaaa/1.21.1.json";
    private const string AssetIndexUrl = "https://piston-meta.mojang.com/v1/packages/bbbb/17.json";
    private const string RuntimeManifestUrl = "https://piston-meta.mojang.com/v1/packages/cccc/manifest.json";

    /// <summary>The truth: path inside an Origin version root -> bytes.</summary>
    public Dictionary<string, byte[]> Files { get; } = new();

    public RecordingHttp Http { get; } = new();

    public IEnumerable<string> Required => Files.Keys;

    public Mojang()
    {
        Bytes("versions/1.21.1/1.21.1.jar", "CLIENT-JAR " + new string('c', 3000));
        Bytes("libraries/com/a/b/1/b-1.jar", "LIB-A " + new string('a', 2000));
        Bytes("libraries/org/c/d/1/d-1.jar", "LIB-D " + new string('d', 1500));
        Bytes("libraries/com/mojang/authlib/6.0.54/authlib-6.0.54.jar", "AUTHLIB " + new string('u', 700)); // "auth" in its name: still a game file
        Bytes("libraries/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar", "NATIVE " + new string('n', 1000));
        Bytes("libraries/old/native/1/native-1-natives-windows.jar", "OLDNATIVE " + new string('o', 800));
        for (var i = 1; i <= 3; i++) Asset("asset-content-" + i + new string('x', 400 * i));
        Bytes("runtime/windows-x64/java-runtime-delta/bin/javaw.exe", "JAVAW " + new string('j', 5000));
        Bytes("runtime/windows-x64/java-runtime-delta/lib/modules", "MODULES " + new string('m', 9000));
        Bytes("runtime/windows-x64/java-runtime-delta/release", "RELEASE");

        var assetObjects = Files.Keys.Where(k => k.StartsWith("assets/objects/")).OrderBy(k => k).ToList();
        var indexJson = JsonSerializer.Serialize(new
        {
            objects = assetObjects.ToDictionary(k => "minecraft/" + k[^4..], k => new { hash = k[^40..], size = Files[k].Length }),
        });
        Files["assets/indexes/17.json"] = Encoding.UTF8.GetBytes(indexJson);

        var versionJson = JsonSerializer.Serialize(new
        {
            id = Version,
            downloads = new { client = Entry("versions/1.21.1/1.21.1.jar", "https://piston-data.mojang.com/v1/objects/zz/client.jar") },
            libraries = new object[]
            {
                new { name = "com.a:b:1", downloads = new { artifact = Artifact("com/a/b/1/b-1.jar") } },
                new { name = "com.mojang:authlib:6.0.54", downloads = new { artifact = Artifact("com/mojang/authlib/6.0.54/authlib-6.0.54.jar") } },
                new
                {
                    name = "org.c:d:1", downloads = new { artifact = Artifact("org/c/d/1/d-1.jar") },
                    rules = new object[] { new { action = "allow" }, new { action = "disallow", os = new { name = "osx" } } },
                },
                new
                {
                    name = "mac:only:1", downloads = new { artifact = new { path = "mac/only/1/only-1.jar", sha1 = new string('0', 40), size = 5, url = "https://libraries.minecraft.net/mac.jar" } },
                    rules = new object[] { new { action = "allow", os = new { name = "osx" } } },
                },
                new
                {
                    name = "org.lwjgl:lwjgl:3.3.3:natives-windows", downloads = new { artifact = Artifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar") },
                    rules = new object[] { new { action = "allow", os = new { name = "windows" } } },
                },
                new
                {
                    name = "org.lwjgl:lwjgl:3.3.3:natives-windows-x86",
                    downloads = new { artifact = new { path = "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows-x86.jar", sha1 = new string('1', 40), size = 5, url = "https://libraries.minecraft.net/x86.jar" } },
                    rules = new object[] { new { action = "allow", os = new { name = "windows", arch = "x86" } } },
                },
                new
                {
                    name = "old:native:1", downloads = new { classifiers = new Dictionary<string, object> { ["natives-windows"] = Artifact("old/native/1/native-1-natives-windows.jar") } },
                    natives = new { windows = "natives-windows" },
                },
            },
            assetIndex = new { id = "17", sha1 = EngineBKit.Sha1(Files["assets/indexes/17.json"]), size = Files["assets/indexes/17.json"].Length, url = AssetIndexUrl },
            javaVersion = new { component = Component, majorVersion = 21 },
        });
        Doc(VersionJsonUrl, versionJson);
        Http.Docs[AssetIndexUrl] = Files["assets/indexes/17.json"];

        var runtimeFiles = new Dictionary<string, object>
        {
            ["bin"] = new { type = "directory" },
            ["bin/javaw.exe"] = Raw("runtime/windows-x64/java-runtime-delta/bin/javaw.exe"),
            ["lib"] = new { type = "directory" },
            ["lib/modules"] = Raw("runtime/windows-x64/java-runtime-delta/lib/modules"),
            ["release"] = Raw("runtime/windows-x64/java-runtime-delta/release"),
            ["lib/link"] = new { type = "link", target = "modules" },
        };
        var runtimeManifest = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { files = runtimeFiles }));
        Http.Docs[RuntimeManifestUrl] = runtimeManifest;
        Doc(MetadataClient.JavaRuntimeListUrl, JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["windows-x64"] = new Dictionary<string, object>
            {
                [Component] = new[] { new { manifest = new { sha1 = EngineBKit.Sha1(runtimeManifest), size = runtimeManifest.Length, url = RuntimeManifestUrl } } },
            },
        }));
        Doc(MetadataClient.VersionManifestUrl, JsonSerializer.Serialize(new
        {
            latest = new { release = Version },
            versions = new[] { new { id = Version, url = VersionJsonUrl, sha1 = EngineBKit.Sha1(Encoding.UTF8.GetBytes(versionJson)) } },
        }));
    }

    private void Bytes(string path, string content) => Files[path] = Encoding.UTF8.GetBytes(content);

    private void Asset(string content)
    {
        var bytes = Encoding.UTF8.GetBytes(content);
        var hash = EngineBKit.Sha1(bytes);
        Files[$"assets/objects/{hash[..2]}/{hash}"] = bytes;
    }

    private void Doc(string url, string json) => Http.Docs[url] = Encoding.UTF8.GetBytes(json);

    private object Entry(string path, string url) => new { sha1 = EngineBKit.Sha1(Files[path]), size = Files[path].Length, url };

    private object Artifact(string libraryPath) => new
    {
        path = libraryPath, sha1 = EngineBKit.Sha1(Files["libraries/" + libraryPath]), size = Files["libraries/" + libraryPath].Length,
        url = "https://libraries.minecraft.net/" + libraryPath,
    };

    private object Raw(string path) => new
    {
        type = "file", executable = false,
        downloads = new { raw = new { sha1 = EngineBKit.Sha1(Files[path]), size = Files[path].Length, url = "https://piston-data.mojang.com/v1/objects/rt/" + Path.GetFileName(path) } },
    };

    // ---- layouts ----

    /// <summary>The official launcher's .minecraft (+ optionally a runtime root: runtime/&lt;component&gt;/windows-x64/&lt;component&gt;/...).</summary>
    public void WriteOfficial(string mcDir, string? runtimeRoot = null, Func<string, bool>? skip = null)
    {
        foreach (var (rel, bytes) in Files)
        {
            if (skip?.Invoke(rel) == true) continue;
            if (rel.StartsWith("runtime/"))
            {
                var sub = rel["runtime/windows-x64/java-runtime-delta/".Length..];
                Kit.WriteBytes(runtimeRoot ?? Path.Combine(mcDir, "runtime"), $"{Component}/windows-x64/{Component}/{sub}", bytes);
            }
            else Kit.WriteBytes(mcDir, rel, bytes);
        }
    }

    public void WritePrism(string dataDir)
    {
        foreach (var (rel, bytes) in Files)
        {
            if (rel.StartsWith("runtime/") || rel.StartsWith("versions/")) continue;
            Kit.WriteBytes(dataDir, rel, bytes);
        }
        Kit.WriteBytes(dataDir, "libraries/com/mojang/minecraft/1.21.1/minecraft-1.21.1-client.jar", Files["versions/1.21.1/1.21.1.jar"]);
    }

    /// <summary>Another Origin version's folder (instances/&lt;v&gt;), which keeps the runtime under runtime/windows-x64/&lt;component&gt;.</summary>
    public void WriteOriginVersion(string instances, string version)
    {
        foreach (var (rel, bytes) in Files) Kit.WriteBytes(Path.Combine(instances, version), rel, bytes);
    }

    public string AssetPath(int n) => Files.Keys.Where(k => k.StartsWith("assets/objects/")).OrderBy(k => k).ElementAt(n - 1);
}
