using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Sync;

/// <summary>A file Settings Sync carries between instances. Id = its path inside an instance, '/'-separated.</summary>
public sealed record SyncedFile(string Id, ISyncFormat Format)
{
    public string PathIn(string instanceDirectory) =>
        Path.Combine(instanceDirectory, Id.Replace('/', Path.DirectorySeparatorChar));
}

// THE list of what syncs. Everything else in an instance — world saves,
// hotbar.nbt, screenshots, resource/shader pack files, other mods' configs —
// stays per version.
public static class SyncedFiles
{
    private static readonly SyncedFile[] Fixed =
    {
        // Vanilla settings: keybinds, mouse, FOV, sound, GUI scale, chat, video.
        new("options.txt", OptionsTxtFormat.Instance),
        // Multiplayer server list.
        new("servers.dat", WholeFileFormat.ServerList),
        // The bundled perf/shader stack's user-visible video settings. Lithium,
        // FerriteCore, Krypton, ImmediatelyFast and ModernFix have none (their
        // configs are internal mixin switches), so they are not synced.
        new("config/sodium-options.json", JsonConfigFormat.ThirdParty),
        new("config/iris.properties", PropertiesFormat.Iris),
    };

    // Origin's own configs: originclient.json (feature flags),
    // originclient-mods.json (mod settings + HUD layout +
    // profiles), originclient-waypoints.json, originclient-itemsizes.json, and
    // any originclient-*.json a later build adds. The ".tmp" siblings the mods
    // write atomically never match.
    private static readonly Regex OriginConfigName = new(@"^originclient[A-Za-z0-9_.\-]*\.json$", RegexOptions.CultureInvariant);

    // Origin configs whose loaders never drop a key by themselves, so a key
    // that disappears was removed by the player (see JsonConfigFormat).
    // originclient.json is NOT one: it is Gson into a class, which drops any
    // field an older module doesn't have.
    private static readonly HashSet<string> DeletionAwareOriginConfigs = new(StringComparer.Ordinal)
    {
        "originclient-mods.json", "originclient-itemsizes.json",
    };

    // Subtrees of an Origin config that describe THIS instance's own files, so they never
    // leave it. originclient-packhub.json is keyed by filenames in this account+version's
    // resourcepacks/ folder: "installed" says which Modrinth file each one is, and
    // "pendingDelete" lists packs the player uninstalled while the game still had them open,
    // deleted at the next start. Synced, an uninstall in one version deleted the same-named
    // pack in every other version (KD-2). Its "presets" (named pack lists) still sync.
    private static readonly Dictionary<string, string[]> LocalKeys = new(StringComparer.Ordinal)
    {
        ["originclient-packhub.json"] = new[] { "/installed", "/pendingDelete" },
    };

    // Origin configs the game deliberately rewrites whole, and the key that then changes
    // (see ISyncFormat.WholeReplaceMarker). ModsConfig.restoreFrom (applying a mod profile)
    // writes a new /meta/profileApplied each time, in every live module.
    private static readonly Dictionary<string, string> WholeReplaceMarkers = new(StringComparer.Ordinal)
    {
        ["originclient-mods.json"] = "/meta/profileApplied",
    };

    // Origin configs with a subtree of entries the player creates and deletes whole
    // (see ISyncFormat.WholeEntrySubtree): each saved mod profile is a full snapshot.
    private static readonly Dictionary<string, string> WholeEntrySubtrees = new(StringComparer.Ordinal)
    {
        ["originclient-mods.json"] = "/profiles",
    };

    // The starter originclient-mods.json a damaged file restarts from (ModsConfig), so a restart can be told from the
    // player deleting every profile (ISyncFormat.Starter, N94-1). The 1.21.1 module's copy (the baseplate), linked into
    // the launcher as an embedded resource by every launcher csproj and the tests; other modules' starters differ in a
    // few keys at most, which only makes those keys weaker evidence. Read once; null if the resource is missing.
    internal const string ModsStarterResource = "OriginLauncher.ModsStarter.json";
    private static readonly Lazy<IReadOnlyDictionary<string, string>?> ModsStarterValues = new(() =>
    {
        try
        {
            using var stream = typeof(SyncedFiles).Assembly.GetManifestResourceStream(ModsStarterResource);
            if (stream == null) return null;
            using var reader = new StreamReader(stream);
            return JsonConfigFormat.FlattenText(reader.ReadToEnd());
        }
        catch (IOException) { return null; }
    });

    /// <summary>The flattened starter originclient-mods.json, or null when the launcher was built without it.</summary>
    public static IReadOnlyDictionary<string, string>? ModsStarter => ModsStarterValues.Value;

    /// <summary>The fixed files plus every Origin config found in any instance or already in the store.</summary>
    public static IReadOnlyList<SyncedFile> All(IEnumerable<SyncInstance> instances, SyncStoreData store)
    {
        var originNames = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var instance in instances)
        {
            var configDirectory = Path.Combine(instance.Directory, "config");
            try
            {
                if (!Directory.Exists(configDirectory)) continue;
                foreach (var file in Directory.EnumerateFiles(configDirectory, "originclient*.json"))
                    originNames.Add(Path.GetFileName(file));
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // an unreadable config folder just contributes no names
            }
        }
        foreach (var id in store.Files.Keys)
        {
            if (id.StartsWith("config/", StringComparison.Ordinal))
                originNames.Add(id["config/".Length..]);
        }

        var files = new List<SyncedFile>(Fixed);
        foreach (var name in originNames)
        {
            if (!OriginConfigName.IsMatch(name)) continue;
            files.Add(new SyncedFile("config/" + name, JsonConfigFormat.OriginConfig(
                allowsDeletions: DeletionAwareOriginConfigs.Contains(name),
                localKeys: LocalKeys.GetValueOrDefault(name),
                wholeReplaceMarker: WholeReplaceMarkers.GetValueOrDefault(name),
                wholeEntrySubtree: WholeEntrySubtrees.GetValueOrDefault(name),
                starter: name == "originclient-mods.json" ? ModsStarter : null)));
        }
        return files;
    }
}
