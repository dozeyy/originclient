using System.IO;
using System.Text.Json;
using CmlLib.Core;
using CmlLib.Core.VersionLoader;
using CmlLib.Core.VersionMetadata;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// Resolves the launched version from the version JSONs already on disk, without
/// asking Mojang, when the whole chain is there; otherwise exactly CmlLib's loader.
///
/// CmlLib's MojangJsonVersionLoaderV2 downloads version_manifest_v2.json on every
/// launch (a blocking GET before any file check can start) only to list versions
/// and SHA-1 the local vanilla JSON against it. The launcher uses this only on the
/// fast path (InstallVerification: this instance already launched clean under this
/// launcher build), the same trust the fast path already gives every other game
/// file (exists + right size). A first launch, a launch after a crash and the
/// first launch after a launcher update stay on the full path: manifest, SHA-1
/// check and re-download of a damaged JSON, as before.
///
/// "On disk" means: versions/&lt;name&gt;/&lt;name&gt;.json exists and parses for the
/// launched version and every inheritsFrom parent (Fabric -> vanilla). Anything
/// missing or unreadable falls back to the Mojang loader for this launch.
/// </summary>
public sealed class LocalFirstVersionLoader : IVersionLoader
{
    private const int MaxChain = 8;

    private readonly MinecraftPath _path;
    private readonly string _versionName;
    private readonly IVersionLoader _fallback;

    public LocalFirstVersionLoader(MinecraftPath path, string versionName, IVersionLoader fallback)
    {
        _path = path;
        _versionName = versionName;
        _fallback = fallback;
    }

    public ValueTask<VersionMetadataCollection> GetVersionMetadatasAsync(CancellationToken cancellationToken = default)
    {
        if (ChainIsLocal(_path.Versions, _versionName))
            return new LocalJsonVersionLoader(_path).GetVersionMetadatasAsync(cancellationToken);
        return _fallback.GetVersionMetadatasAsync(cancellationToken);
    }

    /// <summary>True when &lt;name&gt; and every inheritsFrom parent has a readable JSON under versionsDir.</summary>
    public static bool ChainIsLocal(string versionsDir, string name)
    {
        try
        {
            for (var i = 0; i < MaxChain && !string.IsNullOrEmpty(name); i++)
            {
                var file = Path.Combine(versionsDir, name, name + ".json");
                if (!File.Exists(file)) return false;
                using var doc = JsonDocument.Parse(File.ReadAllBytes(file));
                var root = doc.RootElement;
                if (root.ValueKind != JsonValueKind.Object) return false;
                name = root.TryGetProperty("inheritsFrom", out var parent) && parent.ValueKind == JsonValueKind.String
                    ? parent.GetString() ?? ""
                    : "";
                if (name.Length == 0) return true;
            }
            return false; // a chain longer than any real one: let CmlLib decide
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return false;
        }
    }
}
