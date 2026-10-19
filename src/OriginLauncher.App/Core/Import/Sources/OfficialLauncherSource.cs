using System.Globalization;
using System.IO;
using System.Text.Json;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

// The official Minecraft launcher's .minecraft folder: one candidate per entry of
// launcher_profiles.json.
//
// SECURITY (D6): launcher_profiles.json also holds the launcher's
// authenticationDatabase, clientToken and selectedUser. This reader walks the
// file as a token stream and takes ONLY profiles.*.{name,type,lastVersionId,
// lastUsed,gameDir}; every other value is skipped without ever being turned
// into a string, so a token cannot end up in a plan, a log or a summary.
// launcher_accounts*.json and the other credential stores are never opened at
// all (CredentialGuard).
//
// The version a profile runs: lastVersionId (or, for the two "latest" profiles,
// the launcher's version manifest, else the newest release/snapshot installed).
// An id like fabric-loader-0.16.10-1.21.1 is read for its loader, and
// versions/<id>/<id>.json's inheritsFrom names the Minecraft version (its
// libraries name the loader when the id was renamed).
//
// That is only the launcher's statement: SetupResolver weighs it against the
// folder's log and mods, and collapses profiles that share a game folder (the
// default .minecraft, typically "Latest release" + "Latest snapshot" + a custom
// one) into ONE setup with the other profile names as aliases. A never-played
// profile on a folder with no game data is not a setup at all.
public sealed class OfficialLauncherSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.OfficialLauncher;

    // lastUsed at or before this day is the launcher's "never played" default (1970-01-02).
    private static readonly DateTimeOffset NeverPlayedBefore = new(1970, 1, 2, 0, 0, 1, TimeSpan.Zero);

    private static readonly Regex FabricId = new(@"^fabric-loader-(?<loader>[^-]+)-(?<mc>.+)$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex QuiltId = new(@"^quilt-loader-(?<loader>[^-]+)-(?<mc>.+)$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex ForgeId = new(@"^(?<mc>\d+\.\d+(\.\d+)?)-[Ff]orge-?(?<loader>.+)$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex NeoForgeId = new(@"^neoforge-(?<loader>.+)$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex PlainRelease = new(@"^\d+\.\d+(\.\d+)?$", RegexOptions.CultureInvariant | RegexOptions.Compiled);
    // A real Minecraft version id: 1.21.1, 1.21-pre1, 1.21.1-rc1, 26.4-snapshot-3, 26.1-pre-2, 24w14a. NOT a custom
    // install like "1.20.1-OptiFine_HD_U_I6" or "1.20.1-forge-47.2.0" (those resolve through their version json).
    private static readonly Regex PlainMinecraft = new(@"^(\d+\.\d+(\.\d+)?(-(pre|rc)-?\d+|-snapshot-\d+)?|\d{2}w\d{2}[a-z])$", RegexOptions.CultureInvariant | RegexOptions.Compiled);

    private sealed class RawProfile
    {
        public string Id = "";
        public string? Name, Type, LastVersionId, LastUsed, GameDir;
    }

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(roots.OfficialMinecraftDir)) return Task.CompletedTask;
        return Task.Run(() => DetectIn(roots.OfficialMinecraftDir, fs, into, ct), ct);
    }

    /// <summary>Detects the profiles of one .minecraft folder. Also used by Browse when the folder picked is one.</summary>
    public static void DetectIn(string minecraftDir, SourceFs fs, ScanResult into, CancellationToken ct)
    {
        var profilesFile = Path.Combine(minecraftDir, "launcher_profiles.json");
        if (!File.Exists(profilesFile)) return; // not installed / not an official folder: nothing here

        var profiles = ReadProfiles(profilesFile, fs, into);
        if (profiles.Count == 0) return;

        var manifest = new Lazy<(string? Release, string? Snapshot)>(() => ReadManifest(minecraftDir, fs));
        var installed = new Lazy<List<string>>(() => InstalledVersions(minecraftDir));
        var resolved = new Dictionary<string, InferredVersion?>(StringComparer.Ordinal); // 200 profiles on one version read its json once

        var members = new List<SetupMember>();
        foreach (var profile in profiles)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                members.Add(ToMember(profile, minecraftDir, fs, manifest, installed, resolved));
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException or ArgumentException)
            {
                // One damaged profile never breaks the scan.
                members.Add(new SetupMember(new DetectedInstance
                {
                    Kind = SourceKind.OfficialLauncher, Name = profile.Name is { Length: > 0 } n ? n : profile.Id,
                    GameDir = minecraftDir, Problem = DetectedInstance.CouldntRead,
                }) { NoInference = true });
            }
        }

        // Profiles sharing a game folder (the default .minecraft very often) become ONE setup with aliases;
        // never-played profiles on an empty folder are not setups at all (SetupResolver).
        SetupResolver.AddSetups(into, members, fs, ct, new ResolveOptions { DropEmptyNeverPlayed = true, FlagMissingFolder = true });
    }

    private static SetupMember ToMember(RawProfile p, string minecraftDir, SourceFs fs,
        Lazy<(string? Release, string? Snapshot)> manifest, Lazy<List<string>> installed, Dictionary<string, InferredVersion?> cache)
    {
        var name = !string.IsNullOrWhiteSpace(p.Name) ? p.Name!.Trim()
            : p.Type == "latest-release" ? "Latest release"
            : p.Type == "latest-snapshot" ? "Latest snapshot"
            : p.Id;

        string gameDir;
        if (string.IsNullOrWhiteSpace(p.GameDir)) gameDir = minecraftDir;
        else if (Path.IsPathRooted(p.GameDir)) gameDir = SourceHelpers.Normalize(p.GameDir) ?? minecraftDir;
        else gameDir = SourceHelpers.Normalize(Path.Combine(minecraftDir, p.GameDir)) ?? minecraftDir;

        DateTimeOffset? lastPlayed = null;
        if (!string.IsNullOrWhiteSpace(p.LastUsed)
            && DateTimeOffset.TryParse(p.LastUsed, CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var used)
            && used >= NeverPlayedBefore)
            lastPlayed = used;

        // latest-release / latest-snapshot resolve to a concrete version — but only "whatever is newest today",
        // not necessarily what the folder last ran, so that statement is not Explicit (EvidenceJudge V1/V4).
        var versionId = p.LastVersionId;
        var latest = false;
        if (p.Type == "latest-release" || versionId == "latest-release")
        {
            versionId = manifest.Value.Release ?? NewestInstalled(installed.Value, release: true);
            latest = true;
        }
        else if (p.Type == "latest-snapshot" || versionId == "latest-snapshot")
        {
            versionId = manifest.Value.Snapshot ?? NewestInstalled(installed.Value, release: false);
            latest = true;
        }

        var instance = new DetectedInstance
        {
            Kind = SourceKind.OfficialLauncher, Name = name, GameDir = gameDir, LastPlayed = lastPlayed,
        };

        InferredVersion? resolved = null;
        if (!string.IsNullOrWhiteSpace(versionId) && !cache.TryGetValue(versionId!, out resolved))
            cache[versionId!] = resolved = ResolveVersion(versionId!, minecraftDir, fs);

        // An unresolvable id ("some-modpack-build-7") is no statement at all: the folder's own evidence decides.
        var metadata = resolved?.McVersion == null
            ? Array.Empty<VersionEvidence>()
            : new[]
            {
                new VersionEvidence(EvidenceSource.LauncherMetadata, resolved.McVersion, resolved.Loader, resolved.LoaderVersion, lastPlayed)
                {
                    Explicit = !latest, From = name,
                },
            };
        return new SetupMember(instance) { Metadata = metadata, IsLatestProfile = p.Type is "latest-release" or "latest-snapshot" };
    }

    // ---- launcher_profiles.json: profiles.*.{name,type,lastVersionId,lastUsed,gameDir} and NOTHING else ----

    private static List<RawProfile> ReadProfiles(string path, SourceFs fs, ScanResult into)
    {
        var profiles = new List<RawProfile>();
        using var stream = fs.OpenRead(path);
        if (stream == null)
        {
            into.Notes.Add("Couldn't read the Minecraft launcher's profile list");
            return profiles;
        }
        byte[] bytes;
        try
        {
            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            bytes = buffer.ToArray();
        }
        catch (IOException)
        {
            into.Notes.Add("Couldn't read the Minecraft launcher's profile list");
            return profiles;
        }

        try
        {
            ScanProfiles(bytes, profiles);
        }
        catch (JsonException)
        {
            // A truncated file: keep the profiles read completely before the damage.
            into.Notes.Add("The Minecraft launcher's profile list looks damaged; some profiles may be missing");
        }
        return profiles;
    }

    private static void ScanProfiles(byte[] bytes, List<RawProfile> into)
    {
        var reader = new Utf8JsonReader(bytes, new JsonReaderOptions
        {
            AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip,
        });
        if (!reader.Read() || reader.TokenType != JsonTokenType.StartObject) return;
        while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
        {
            var isProfiles = reader.ValueTextEquals("profiles");
            reader.Read();
            if (isProfiles && reader.TokenType == JsonTokenType.StartObject) ReadProfileMap(ref reader, into);
            else reader.Skip(); // authenticationDatabase, clientToken, selectedUser, settings...: never materialised
        }
    }

    private static void ReadProfileMap(ref Utf8JsonReader reader, List<RawProfile> into)
    {
        while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
        {
            var profile = new RawProfile { Id = reader.GetString() ?? "" };
            reader.Read();
            if (reader.TokenType != JsonTokenType.StartObject) { reader.Skip(); continue; }
            while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
            {
                string? target = null;
                if (reader.ValueTextEquals("name")) target = "name";
                else if (reader.ValueTextEquals("type")) target = "type";
                else if (reader.ValueTextEquals("lastVersionId")) target = "lastVersionId";
                else if (reader.ValueTextEquals("lastUsed")) target = "lastUsed";
                else if (reader.ValueTextEquals("gameDir")) target = "gameDir";
                reader.Read();
                if (target == null || reader.TokenType != JsonTokenType.String) { reader.Skip(); continue; }
                var value = reader.GetString();
                switch (target)
                {
                    case "name": profile.Name = value; break;
                    case "type": profile.Type = value; break;
                    case "lastVersionId": profile.LastVersionId = value; break;
                    case "lastUsed": profile.LastUsed = value; break;
                    case "gameDir": profile.GameDir = value; break;
                }
            }
            into.Add(profile); // only reached for a profile read to its closing brace
        }
    }

    // ---- version resolution ----

    private static InferredVersion? ResolveVersion(string versionId, string minecraftDir, SourceFs fs)
    {
        var loader = LoaderKind.None;
        string? loaderVersion = null, mc = null;

        Match m;
        if ((m = FabricId.Match(versionId)).Success)
        { loader = LoaderKind.Fabric; loaderVersion = m.Groups["loader"].Value; mc = m.Groups["mc"].Value; }
        else if ((m = QuiltId.Match(versionId)).Success)
        { loader = LoaderKind.Quilt; loaderVersion = m.Groups["loader"].Value; mc = m.Groups["mc"].Value; }
        else if ((m = ForgeId.Match(versionId)).Success)
        { loader = LoaderKind.Forge; loaderVersion = m.Groups["loader"].Value; mc = m.Groups["mc"].Value; }
        else if ((m = NeoForgeId.Match(versionId)).Success)
        { loader = LoaderKind.NeoForge; loaderVersion = m.Groups["loader"].Value; }
        else if (PlainMinecraft.IsMatch(versionId))
        { mc = versionId; }

        // The version json is the launcher's own statement: inheritsFrom names the Minecraft version (followed a
        // few levels for a custom id that inherits from another custom id), and when the id was RENAMED
        // ("My Fabric", "fabric-custom") its libraries still name the loader (net.fabricmc:fabric-loader:0.16.10).
        var id = versionId;
        for (var depth = 0; depth < 4; depth++)
        {
            var json = ReadVersionJson(Path.Combine(minecraftDir, "versions", id, id + ".json"), fs);
            if (json == null) break;
            if (loader == LoaderKind.None && json.Value.Loader != LoaderKind.None)
            { loader = json.Value.Loader; loaderVersion = json.Value.LoaderVersion; }
            if (json.Value.InheritsFrom is not { Length: > 0 } parent) break;
            mc = parent;
            if (PlainMinecraft.IsMatch(parent)) break;
            id = parent;
        }
        if (mc != null && !PlainMinecraft.IsMatch(mc)) mc = null; // a chain that ends on an unknown custom id names no version

        return mc == null ? null : new InferredVersion(mc, loader, loaderVersion);
    }

    // Takes only the top-level "inheritsFrom" and libraries[].name of a version json (they run to hundreds of KB).
    private static (string? InheritsFrom, LoaderKind Loader, string? LoaderVersion)? ReadVersionJson(string jsonPath, SourceFs fs)
    {
        if (!File.Exists(jsonPath)) return null;
        using var stream = fs.OpenRead(jsonPath);
        if (stream == null) return null;
        string? inherits = null;
        var loader = LoaderKind.None;
        string? loaderVersion = null;
        try
        {
            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            var reader = new Utf8JsonReader(buffer.ToArray(), new JsonReaderOptions { AllowTrailingCommas = true, CommentHandling = JsonCommentHandling.Skip });
            if (!reader.Read() || reader.TokenType != JsonTokenType.StartObject) return null;
            while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
            {
                var isInherits = reader.ValueTextEquals("inheritsFrom");
                var isLibraries = reader.ValueTextEquals("libraries");
                reader.Read();
                if (isInherits && reader.TokenType == JsonTokenType.String) { inherits = reader.GetString(); continue; }
                if (isLibraries && reader.TokenType == JsonTokenType.StartArray)
                {
                    while (reader.Read() && reader.TokenType != JsonTokenType.EndArray)
                    {
                        if (reader.TokenType != JsonTokenType.StartObject) { reader.Skip(); continue; }
                        while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
                        {
                            var isName = reader.ValueTextEquals("name");
                            reader.Read();
                            if (isName && reader.TokenType == JsonTokenType.String && loader == LoaderKind.None)
                                (loader, loaderVersion) = LoaderFromLibrary(reader.GetString());
                            else reader.Skip();
                        }
                    }
                    continue;
                }
                reader.Skip();
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException or InvalidOperationException)
        {
            // damaged version json: keep what was read before the damage; the id's own shape still counts
        }
        return (inherits, loader, loaderVersion);
    }

    // "net.fabricmc:fabric-loader:0.16.10" -> Fabric 0.16.10; quilt-loader, net.neoforged:neoforge, net.minecraftforge:forge (mc-version).
    private static (LoaderKind, string?) LoaderFromLibrary(string? name)
    {
        if (string.IsNullOrEmpty(name)) return (LoaderKind.None, null);
        var parts = name.Split(':');
        if (parts.Length < 3) return (LoaderKind.None, null);
        var version = parts[2];
        return (parts[0], parts[1]) switch
        {
            ("net.fabricmc", "fabric-loader") => (LoaderKind.Fabric, version),
            ("org.quiltmc", "quilt-loader") => (LoaderKind.Quilt, version),
            ("net.neoforged", "neoforge") or ("net.neoforged", "forge") => (LoaderKind.NeoForge, version),
            ("net.minecraftforge", "forge") or ("net.minecraftforge", "fmlloader") =>
                (LoaderKind.Forge, version.Contains('-') ? version[(version.IndexOf('-') + 1)..] : version),
            _ => (LoaderKind.None, null),
        };
    }

    private static (string? Release, string? Snapshot) ReadManifest(string minecraftDir, SourceFs fs)
    {
        var path = Path.Combine(minecraftDir, "versions", "version_manifest_v2.json");
        if (!File.Exists(path)) path = Path.Combine(minecraftDir, "versions", "version_manifest.json");
        if (!File.Exists(path)) return (null, null);
        using var stream = fs.OpenRead(path);
        if (stream == null) return (null, null);
        try
        {
            // The manifest is ~600 KB of version entries; only its small "latest" object is wanted.
            using var buffer = new MemoryStream();
            stream.CopyTo(buffer);
            var reader = new Utf8JsonReader(buffer.ToArray(), new JsonReaderOptions { AllowTrailingCommas = true });
            if (!reader.Read() || reader.TokenType != JsonTokenType.StartObject) return (null, null);
            while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
            {
                var isLatest = reader.ValueTextEquals("latest");
                reader.Read();
                if (!isLatest || reader.TokenType != JsonTokenType.StartObject) { reader.Skip(); continue; }
                string? release = null, snapshot = null;
                while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
                {
                    var key = reader.GetString();
                    reader.Read();
                    if (reader.TokenType != JsonTokenType.String) { reader.Skip(); continue; }
                    if (key == "release") release = reader.GetString();
                    else if (key == "snapshot") snapshot = reader.GetString();
                }
                return (release, snapshot);
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException)
        {
            // fall back to what is installed
        }
        return (null, null);
    }

    private static List<string> InstalledVersions(string minecraftDir)
    {
        var versions = Path.Combine(minecraftDir, "versions");
        if (!Directory.Exists(versions)) return new List<string>();
        try
        {
            return Directory.EnumerateDirectories(versions).Select(d => Path.GetFileName(d)!).ToList();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new List<string>();
        }
    }

    // The newest installed release (or, for a snapshot profile, the newest version of any kind).
    private static string? NewestInstalled(List<string> installed, bool release)
    {
        var pool = installed.Where(v => release ? PlainRelease.IsMatch(v) : PlainMinecraft.IsMatch(v)).ToList();
        if (pool.Count == 0) return null;
        return pool.OrderByDescending(v => v, Comparer<string>.Create(Sync.McVersion.Compare)).First();
    }
}
