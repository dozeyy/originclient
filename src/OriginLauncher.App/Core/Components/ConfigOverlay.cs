using System.IO;

namespace OriginLauncher.App.Core.Components;

/// <summary>
/// Each configuration keeps its own Sodium/Iris settings files. An older Sodium reads (and rewrites) the shipped
/// one's sodium-options.json, so before a launch the files of the configuration about to run are copied into the
/// account's config folder, and after the game exits the shipped set's files are copied back. The player's own
/// files for the shipped set are saved first, so they come back exactly.
///
/// Layout (OriginPaths.ConfigOverlays): &lt;overlayRoot&gt;/&lt;configurationId&gt;/&lt;file&gt; holds each configuration's
/// saved copy; &lt;overlayRoot&gt;/active.txt names the configuration whose files are in the config folder now (no
/// file = the shipped set). A switch writes "pending:&lt;target&gt;" before it starts loading, so a crash halfway is
/// finished by the next call instead of leaving a mix.
/// The usual launch (shipped set, nothing switched) costs one File.Exists.
/// </summary>
public static class ConfigOverlay
{
    /// <summary>The files a Sodium/Iris pair owns in config/.</summary>
    public static readonly IReadOnlyList<string> Files = new[]
    {
        "sodium-options.json", "sodium-mixins.properties", "sodium-fingerprint.json", "iris.properties",
    };

    private const string MarkerName = "active.txt";
    private const string PendingPrefix = "pending:";

    /// <summary>Makes <paramref name="configDir"/> hold <paramref name="targetId"/>'s files.</summary>
    public static void Switch(string configDir, string overlayRoot, string targetId)
    {
        if (!IsSafeId(targetId)) throw new ArgumentException("Not a configuration id.", nameof(targetId));
        var marker = Path.Combine(overlayRoot, MarkerName);
        if (!File.Exists(marker))
        {
            if (targetId == ConfigurationCatalog.DefaultId) return; // the usual case
            Begin(configDir, overlayRoot, ConfigurationCatalog.DefaultId, targetId, marker);
            return;
        }

        var text = File.ReadAllText(marker).Trim();
        if (text.StartsWith(PendingPrefix, StringComparison.Ordinal))
        {
            // A switch was cut short after the active files were saved: finish loading what it was loading.
            var half = text[PendingPrefix.Length..];
            if (IsSafeId(half)) Load(configDir, overlayRoot, half, marker);
            else File.Delete(marker);
            Switch(configDir, overlayRoot, targetId);
            return;
        }
        var active = IsSafeId(text) ? text : ConfigurationCatalog.DefaultId;
        if (active == targetId) return;
        Begin(configDir, overlayRoot, active, targetId, marker);
    }

    /// <summary>What the marker says now ("default", an id, or "pending:&lt;id&gt;"), for the tests and the log.</summary>
    public static string Active(string overlayRoot)
    {
        var marker = Path.Combine(overlayRoot, MarkerName);
        return File.Exists(marker) ? File.ReadAllText(marker).Trim() : ConfigurationCatalog.DefaultId;
    }

    private static void Begin(string configDir, string overlayRoot, string active, string target, string marker)
    {
        // 1. Save what is there now under the active configuration (a file it doesn't have is removed from its copy).
        var saved = Path.Combine(overlayRoot, active);
        Directory.CreateDirectory(saved);
        foreach (var name in Files)
        {
            var live = Path.Combine(configDir, name);
            var copy = Path.Combine(saved, name);
            if (File.Exists(live)) CopyAtomic(live, copy);
            else if (File.Exists(copy)) File.Delete(copy);
        }
        // 2. From here a crash is finished by the next call.
        WriteMarker(marker, PendingPrefix + target);
        // 3. Load the target's copy.
        Load(configDir, overlayRoot, target, marker);
    }

    private static void Load(string configDir, string overlayRoot, string target, string marker)
    {
        var source = Path.Combine(overlayRoot, target);
        Directory.CreateDirectory(configDir);
        foreach (var name in Files)
        {
            var copy = Path.Combine(source, name);
            var live = Path.Combine(configDir, name);
            // A configuration that has never run starts on its own defaults, not on another Sodium's file.
            if (File.Exists(copy)) CopyAtomic(copy, live);
            else if (File.Exists(live)) File.Delete(live);
        }
        if (target == ConfigurationCatalog.DefaultId) File.Delete(marker);
        else WriteMarker(marker, target);
    }

    private static void WriteMarker(string marker, string text)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(marker)!);
        var temp = marker + ".tmp";
        File.WriteAllText(temp, text);
        File.Move(temp, marker, overwrite: true);
    }

    private static void CopyAtomic(string from, string to)
    {
        var temp = to + ".tmp";
        File.Copy(from, temp, overwrite: true);
        File.Move(temp, to, overwrite: true);
    }

    private static bool IsSafeId(string id) =>
        id == ConfigurationCatalog.DefaultId ||
        (id.StartsWith(ConfigurationCatalog.PairFamily + "-", StringComparison.Ordinal) &&
         ComponentPinSet.IsPinId(id[(ConfigurationCatalog.PairFamily.Length + 1)..]));
}

/// <summary>
/// A launch on a configuration: its Sodium/Iris files are in place and it is registered as running. Dispose when
/// the game exits: the shipped set's files come back and the registration ends. Never throws from Dispose.
/// </summary>
public sealed class ActiveConfiguration : IDisposable
{
    private readonly string _configDir;
    private readonly string _overlayRoot;
    private readonly IDisposable _registration;
    private int _done;

    public string Id { get; }

    private ActiveConfiguration(string id, string configDir, string overlayRoot, IDisposable registration)
    {
        Id = id;
        _configDir = configDir;
        _overlayRoot = overlayRoot;
        _registration = registration;
    }

    /// <summary>Puts <paramref name="configurationId"/>'s files in <paramref name="gameDir"/>/config and registers the game.</summary>
    public static ActiveConfiguration Begin(string profileId, string configurationId, string gameDir, string overlayRoot)
    {
        var configDir = Path.Combine(gameDir, "config");
        try { ConfigOverlay.Switch(configDir, overlayRoot, configurationId); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Settings files locked or unwritable: the game still starts (it rewrites what it can't read); the next
            // Switch finishes the swap. A settings copy must never stop Play.
        }
        return new ActiveConfiguration(configurationId, configDir, overlayRoot,
            RunningConfigurations.Started(profileId, configurationId));
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _done, 1) == 1) return;
        try { ConfigOverlay.Switch(_configDir, _overlayRoot, ConfigurationCatalog.DefaultId); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            // Left as it is: the next Switch (the next launch of any configuration) finishes the swap back.
        }
        finally { _registration.Dispose(); }
    }
}
