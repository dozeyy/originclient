using System.Runtime.InteropServices;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// What one Minecraft version can do on a Mac, for the Mac picker and Home to read. A version that plays
/// on a Mac but whose shaders don't stays in the picker and says "Shaders aren't available on Mac yet"
/// (Will, 2026-11-02); <see cref="ShaderNote"/> is that sentence, null when shaders work.
/// </summary>
public sealed record MacVersionCapability(string Version, bool ShadersAvailable, bool NeedsRosettaOnAppleSilicon)
{
    /// <summary>The line the picker shows under a version whose shaders don't work on a Mac, else null.</summary>
    public string? ShaderNote => ShadersAvailable ? null : "Shaders aren't available on Mac yet.";
}

/// <summary>
/// The per-version Mac record. Shaders default to available: a version is set to "no" ONLY with evidence
/// for the exact pinned jars (an Iris/Sodium/OptiFine release note, official FAQ or issue tracker entry),
/// cited next to its entry. Today no pinned version has such evidence, so the table is empty.
///
/// Evidence reviewed 2026-11-02 (pins from Core/Loaders/PerformanceModCatalog.Data.cs and OptiFineInstaller):
///  * Every 1.19-1.21.11 and 26.1.x pair: no report of the pinned Iris/Sodium failing on macOS. Sodium 0.5
///    and 0.6 are reported running on Apple Silicon and Intel Macs (github.com/CaffeineMC/sodium/issues/2732).
///    Iris's FAQ calls macOS "limited support" (a possible crash cause, not a ban):
///    github.com/IrisShaders/Iris/blob/multiloader-new/docs/faq.md. Apple's OpenGL stops at 4.1, so a
///    shader PACK that needs compute shaders (GL 4.3) won't run; that is per pack, not per version.
///  * 1.18.2 / 1.19.x (Sodium 0.4.x) carry a risk, not evidence: Sodium 0.4.0-alpha5 crashed at start on
///    macOS for 1.18.1 (github.com/CaffeineMC/sodium/issues/1100, closed). It names an alpha, not the pinned
///    0.4.1 / 0.4.4 / 0.4.9 / 0.4.10 releases, so those stay "yes" until a Mac run says otherwise.
///  * 26.2 / 26.3: Iris 1.11.0 for 26.2 says Vulkan is not supported (modrinth.com/mod/iris/version/1y9HMMC6),
///    so shaders need the game's OpenGL backend on a Mac too. No source says that backend is missing on
///    macOS, so "yes" (unknown, not disproven).
///  * 1.8.9 (Forge + OptiFine HD_U_M5): no source either way, so "yes".
///  * Not shaders, but GL-version-bound like them: Origin Render's terrain culler (modules 1.21, 1.21.4)
///    needs GL 4.3 features (compute + SSBO). TerrainCuller.supported() checks them on the live context and
///    OriginRender.create() refuses without them, so on a Mac (GL 4.1) it stays off and the menu shows why
///    (src/mods/versions/1.21/.../render/terrain/TerrainCuller.java, supported()); it is also off by default.
/// </summary>
public static class MacVersionCapabilities
{
    // Version -> why its shaders don't work on a Mac (with the source). Empty: see the evidence above.
    private static readonly IReadOnlyDictionary<string, string> ShadersUnavailable =
        new Dictionary<string, string>(StringComparer.Ordinal);

    /// <summary>The Mac record for <paramref name="version"/>; Rosetta is the Apple Silicon answer for it.</summary>
    public static MacVersionCapability For(string version) =>
        new(version,
            ShadersAvailable: !ShadersUnavailable.ContainsKey(version),
            NeedsRosettaOnAppleSilicon: MacLaunchPolicy.For(version, Architecture.Arm64).UnderRosetta);
}
