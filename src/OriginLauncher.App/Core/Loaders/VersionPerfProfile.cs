namespace OriginLauncher.App.Core.Loaders;

public sealed record VersionPerfProfile(
    string McVersion,
    PerfStackTier Tier,
    PerfMod? Sodium,
    PerfMod? Indium,
    PerfMod? Lithium,
    PerfMod? Ferrite,
    PerfMod? Krypton,
    // Iris shader loader — only present on profiles whose Sodium build it is
    // version-compatible with (Iris hard-requires Sodium). Idle overhead with
    // no shaderpack active is ~zero, so it never costs FPS by default.
    PerfMod? Iris = null,
    // ImmediatelyFast (batches immediate-mode draws) and ModernFix (startup +
    // memory). Both were only ever shipped on 1.21.1, bundled jar-in-jar, so
    // every other version ran without them — the cross-version inconsistency
    // found in the 2026-08-01 audit. Now pinned per version wherever a stable
    // Fabric build exists; a version with no build simply leaves them null
    // (ImmediatelyFast: none for 1.16.5/1.17.1 — ModernFix: none for 1.21.2/3,
    // 1.21.5 through 1.21.11, the 26.x line, 1.19.3 or 1.17.1).
    PerfMod? ImmediatelyFast = null,
    PerfMod? ModernFix = null,
    // Mod Menu. Not a perf mod — it is what puts the "Mods" button on the title
    // screen, and 1.21.1 (the design baseline) ships it jar-in-jar. Every other
    // version had no such button, which also shifted the whole button block 24px
    // because vanilla re-centres it: the same screen did not look the same twice.
    // Pinned per version so the baseline layout is what every version shows.
    PerfMod? ModMenu = null,
    // EntityCulling (tr7zw): async occlusion culling of entities and block entities,
    // no visual change, and shadow-pass aware, which is the big win once a shader
    // pack makes Iris draw the world twice. Added 2026-10-20 (speed-mods audit) only
    // where a MAINTAINED stable Fabric build (published 2025 or later) exists for the
    // exact version. Left null on purpose: 1.21.1 (its stack is bundled jar-in-jar and
    // EntityCulling was pulled from that build 2026-07-10), 1.21.2 (no build exists),
    // and 1.16.5 / 1.17.1 / 1.18.2 / 1.19.2 / 1.20 (newest build is from 2022-2023,
    // unverified against that era's Sodium 0.2-0.4 and the PoseStack-era Origin render).
    PerfMod? EntityCulling = null)
{
    public IEnumerable<PerfMod> Mods()
    {
        if (Sodium != null) yield return Sodium;
        if (Indium != null) yield return Indium;
        if (Lithium != null) yield return Lithium;
        if (Ferrite != null) yield return Ferrite;
        if (Krypton != null) yield return Krypton;
        if (Iris != null) yield return Iris;
        if (ImmediatelyFast != null) yield return ImmediatelyFast;
        if (ModernFix != null) yield return ModernFix;
        if (ModMenu != null) yield return ModMenu;
        if (EntityCulling != null) yield return EntityCulling;
    }
}
