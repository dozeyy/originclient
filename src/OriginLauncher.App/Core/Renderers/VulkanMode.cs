using System.IO;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.Core.Renderers;

/// <summary>What Settings shows for the Vulkan graphics toggle.</summary>
public enum VulkanState
{
    /// <summary>Origin's Vulkan jars are bundled and none of the player's mods needs normal graphics.</summary>
    Available,
    /// <summary>This build of Origin carries no Vulkan jars (the vendor modules were not built): the toggle is off and says why.</summary>
    MissingJars,
    /// <summary>One or more of the player's own 1.21.1 mods only works with normal graphics.</summary>
    Blocked,
}

/// <summary>One of the player's mods that keeps Vulkan graphics off: its file, display name and the id that ties it to OpenGL.</summary>
public sealed record VulkanBlocker(string FileName, string Name, string NeedsId);

/// <summary>
/// The rules of 1.21.1's opt-in Vulkan graphics (Settings -> Performance; Will, 2026-11-16). Pure: no files, no
/// network, so every rule here is unit tested (VulkanModeTests). The file work is VulkanRendererFiles and
/// VulkanBlockScan; the launch wiring is VersionManager, the boot fallback HomePage.
///
/// How a Vulkan launch differs from a normal one, and nothing else:
///  * the game loads <c>&lt;account&gt;/mods-vulkan</c>: every jar the normal launch would load, plus VulkanMod (a pin,
///    SHA-512 checked) and Origin's own Vulkan jars (bundled), all as hard links (LaunchModFolder.Sync);
///  * <c>-Dfabric.debug.disableModIds</c> also names sodium and immediatelyfast (the OpenGL-only pieces nested in
///    Origin's jar), merged with whatever the player-override plan already disables;
///  * the player's own mods that need Sodium/Iris/OpenGL keep it off (<see cref="BlockingIds"/>);
///  * a game that dies before its title screen turns the setting off and relaunches once on normal graphics.
/// </summary>
public static class VulkanMode
{
    /// <summary>The one version that reads the setting; every other version ignores it.</summary>
    public const string Version = "1.21.1";

    /// <summary>The renderer folder name under instances/1.21.1/renderers (OriginPaths.RendererDir).</summary>
    public const string RendererName = "vulkan";

    /// <summary>The per-account launch folder a Vulkan game loads (beside mods-shared, mods-profile...).</summary>
    public const string LaunchFolderName = "mods-vulkan";

    /// <summary>
    /// Mod ids the loader skips in a Vulkan game (fabric.debug.disableModIds). Fixed contract with the 1.21.1 module
    /// and CI's renderer=vulkan path (-PdisableModIds=sodium,immediatelyfast): change all three together.
    /// </summary>
    public static readonly IReadOnlyList<string> DisabledIds = ["sodium", "immediatelyfast"];

    /// <summary>
    /// A player's mod that is one of these, or names one in "depends" or "recommends", only works with normal graphics
    /// (Will, 2026-11-16: block Vulkan while Sodium-only mods are installed). "iris" here is a standalone Iris or
    /// anything built on it; Origin's own Iris for Vulkan is bundled and never the player's.
    /// </summary>
    public static readonly IReadOnlySet<string> BlockingIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    {
        "sodium", "indium", "embeddium", "iris", "immediatelyfast", "voxy", "distanthorizons", "satin", "iceberg", "colorwheel",
    };

    /// <summary>
    /// Origin's bundled Vulkan jars (OriginPaths.BundledVulkanDir), one file per pattern. Every pattern must match for
    /// Vulkan graphics to be available.
    /// </summary>
    public static readonly IReadOnlyList<string> BundledPatterns = ["vulkairis-origin-*.jar", "flywheel-vulkan-*.jar"];

    /// <summary>The Home line after a Vulkan game died before its title screen (the fallback relaunch is normal graphics).</summary>
    public const string FallbackLine = "Vulkan didn't start on this PC, so 1.21.1 is back on normal graphics.";

    /// <summary>The Settings line when this build carries no Vulkan jars.</summary>
    public const string MissingJarsLine = "Not available in this version of Origin yet.";

    /// <summary>The Home line when the Vulkan files could not be put in place (no internet on the first Vulkan launch, a bad download).</summary>
    public const string SetupFailedLine = "Vulkan graphics couldn't be set up, so this game uses normal graphics";

    /// <summary>True only for the version the setting applies to.</summary>
    public static bool Applies(string? version) => version == Version;

    /// <summary>Whether this launch should try Vulkan: the setting is on and the version is 1.21.1.</summary>
    public static bool Wanted(bool setting, string? version) => setting && Applies(version);

    /// <summary>
    /// Why <paramref name="mod"/> keeps Vulkan off, or null when it doesn't: it IS one of <see cref="BlockingIds"/>
    /// (the player's own Sodium), provides one, or depends on / recommends one.
    /// </summary>
    public static VulkanBlocker? BlockerFor(ModInfo mod)
    {
        if (mod.ReadFailed) return null; // nothing readable to judge: the loader decides, not this rule
        var name = string.IsNullOrWhiteSpace(mod.Name) ? mod.Id ?? Path.GetFileNameWithoutExtension(mod.FileName) : mod.Name!;
        if (mod.Id != null && BlockingIds.Contains(mod.Id)) return new VulkanBlocker(mod.FileName, name, mod.Id);
        foreach (var provided in mod.Provides)
            if (BlockingIds.Contains(provided)) return new VulkanBlocker(mod.FileName, name, provided);
        foreach (var dep in mod.Depends.Concat(mod.Recommends))
            if (BlockingIds.Contains(dep.Id)) return new VulkanBlocker(mod.FileName, name, dep.Id);
        return null;
    }

    /// <summary>Every blocker among <paramref name="mods"/> (the player's own jars only), in file-name order.</summary>
    public static IReadOnlyList<VulkanBlocker> Blockers(IEnumerable<ModInfo> mods) =>
        mods.Select(BlockerFor).OfType<VulkanBlocker>()
            .OrderBy(b => b.FileName, StringComparer.OrdinalIgnoreCase).ToList();

    /// <summary>The toggle's state from what is known: jars missing beats blocked, and nothing blocks an available build.</summary>
    public static VulkanState StateOf(bool jarsPresent, int blockerCount) =>
        !jarsPresent ? VulkanState.MissingJars
        : blockerCount > 0 ? VulkanState.Blocked
        : VulkanState.Available;

    /// <summary>Up to three names, then "and N more": "Sodium Extra, Xaero's Minimap and 2 more".</summary>
    public static string Names(IReadOnlyList<VulkanBlocker> blockers)
    {
        var names = blockers.Select(b => b.Name).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (names.Count == 0) return "";
        if (names.Count == 1) return names[0];
        if (names.Count <= 3) return string.Join(", ", names.Take(names.Count - 1)) + " and " + names[^1];
        return string.Join(", ", names.Take(3)) + $" and {names.Count - 3} more";
    }

    /// <summary>The Settings line that names the mods keeping the toggle off.</summary>
    public static string BlockedSettingsLine(IReadOnlyList<VulkanBlocker> blockers) =>
        $"Can't turn on while you have {Names(blockers)}: {(Count(blockers) == 1 ? "it only works" : "they only work")} with normal graphics.";

    /// <summary>The Home line when the setting is on but one of the player's mods kept this launch on normal graphics.</summary>
    public static string BlockedHomeLine(IReadOnlyList<VulkanBlocker> blockers) =>
        $"Normal graphics this time: {Names(blockers)} {(Count(blockers) == 1 ? "only works" : "only work")} with it, so Vulkan stayed off";

    private static int Count(IReadOnlyList<VulkanBlocker> blockers) =>
        blockers.Select(b => b.Name).Distinct(StringComparer.OrdinalIgnoreCase).Count();

    /// <summary>
    /// What mods-vulkan holds, as file name -> source path: every .jar the normal launch loads
    /// (<paramref name="baseJars"/>, full paths) plus the renderer's jars (<paramref name="rendererJars"/>, full
    /// paths). A renderer jar wins over a base jar of the same name. Disabled files (.jar.disabled) and anything that
    /// is not a jar stay out.
    /// </summary>
    public static Dictionary<string, string> FolderPlan(IEnumerable<string> baseJars, IEnumerable<string> rendererJars)
    {
        var plan = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var path in baseJars)
        {
            var name = Path.GetFileName(path);
            if (name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) plan[name] = path;
        }
        foreach (var path in rendererJars)
        {
            var name = Path.GetFileName(path);
            if (name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) plan[name] = path;
        }
        return plan;
    }

    /// <summary>
    /// The one fabric.debug.disableModIds argument for a launch: <paramref name="planIds"/> (the player-override plan's,
    /// PlayerOverridePlan.DisabledIds) plus <see cref="DisabledIds"/> when <paramref name="vulkan"/>, each id once, in
    /// that order. Null when there is nothing to disable (a normal launch with an empty plan, exactly as before).
    /// </summary>
    public static string? DisableModIdsArgument(IEnumerable<string> planIds, bool vulkan)
    {
        var ids = new List<string>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var id in planIds.Concat(vulkan ? DisabledIds : Array.Empty<string>()))
            if (!string.IsNullOrWhiteSpace(id) && seen.Add(id.Trim())) ids.Add(id.Trim());
        return ids.Count == 0 ? null : "-Dfabric.debug.disableModIds=" + string.Join(",", ids);
    }

    /// <summary>
    /// The boot fallback: a game launched on Vulkan that stopped with a failure before its title screen (the
    /// handshake never reached Ready) and that the player did not stop. Then the setting goes off and the game is
    /// launched once more on normal graphics. A game that reached the title screen, a normal quit (exit 0) and a
    /// Cancel are never a fallback.
    /// </summary>
    public static bool ShouldFallBack(bool launchedVulkan, HandoffStage reached, bool stoppedByPlayer, int exitCode) =>
        launchedVulkan && !stoppedByPlayer && exitCode != 0 && reached < HandoffStage.Ready;
}
