namespace OriginLauncher.App.Core.Loaders;

public sealed record VersionPerfProfile(
    string McVersion,
    PerfStackTier Tier,
    PerfMod? Sodium,
    PerfMod? Indium,
    PerfMod? Lithium,
    PerfMod? Ferrite,
    PerfMod? Krypton)
{
    public IEnumerable<PerfMod> Mods()
    {
        if (Sodium != null) yield return Sodium;
        if (Indium != null) yield return Indium;
        if (Lithium != null) yield return Lithium;
        if (Ferrite != null) yield return Ferrite;
        if (Krypton != null) yield return Krypton;
    }
}
