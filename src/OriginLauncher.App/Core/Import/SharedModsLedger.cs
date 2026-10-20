using System.IO;
using OriginLauncher.App.Core.Import.Mods;

namespace OriginLauncher.App.Core.Import;

/// <summary>
/// What one Minecraft version's SHARED mods folder (instances/&lt;version&gt;/mods, Core/GameFolders/SharedMods) already
/// holds and what this run puts in it. Imports never make a profile: mods land in that one folder, every account of the
/// version plays them. The folder is only ever ADDED to (a running game of another account may hold a jar, so nothing
/// in it is rewritten): a mod whose id or file name is already there stays as it is, and a mod two setups of one run
/// both bring lands once, from the first.
/// </summary>
public sealed class SharedModsLedger
{
    /// <summary>Who already has a mod: Origin's folder (<see cref="InOrigin"/>) or the label of an earlier setup of this run.</summary>
    public const string InOrigin = "";

    private readonly Dictionary<string, string> _ids = new(StringComparer.Ordinal);
    private readonly Dictionary<string, string> _files = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>The ledger of <paramref name="modsDir"/> as it is now: every jar's file name and mod id. A jar that can't be read still counts by name.</summary>
    public static SharedModsLedger Read(string modsDir)
    {
        var ledger = new SharedModsLedger();
        if (!Directory.Exists(modsDir)) return ledger;
        IEnumerable<string> files;
        try { files = Directory.EnumerateFiles(modsDir).ToList(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return ledger; }
        foreach (var path in files)
        {
            var name = Path.GetFileName(path);
            if (!IsModFile(name)) continue;
            ledger._files.TryAdd(FileKey(name), InOrigin);
            try
            {
                using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                if (ModMetadataReader.Read(stream, name, readNested: false) is { Id: { Length: > 0 } id }) ledger._ids.TryAdd(id, InOrigin);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidDataException)
            {
                // in use or unreadable: its file name still keeps an import from landing on it
            }
        }
        return ledger;
    }

    /// <summary>Who already has this mod (<see cref="InOrigin"/> or a setup's label); null when it is free to land.</summary>
    public string? Holder(ModDecision mod)
    {
        if (mod.ModId is { Length: > 0 } id && _ids.TryGetValue(id, out var byId)) return byId;
        if (mod.DestFileName is { } file && _files.TryGetValue(FileKey(file), out var byFile)) return byFile;
        return null;
    }

    /// <summary>Records that <paramref name="mod"/> lands in this run, brought by <paramref name="setupLabel"/>.</summary>
    public void Add(ModDecision mod, string setupLabel)
    {
        if (mod.ModId is { Length: > 0 } id) _ids.TryAdd(id, setupLabel);
        if (mod.DestFileName is { } file) _files.TryAdd(FileKey(file), setupLabel);
    }

    private static bool IsModFile(string name) =>
        name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || name.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase);

    // "sodium.jar" and "sodium.jar.disabled" are one mod: the turned-off twin blocks the other.
    private static string FileKey(string name) =>
        name.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? name[..^".disabled".Length] : name;
}
