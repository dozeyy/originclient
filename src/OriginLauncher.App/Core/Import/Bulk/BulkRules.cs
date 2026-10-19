using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import.Bulk;

/// <summary>
/// The order setups are planned and applied in, so a bulk import is deterministic: the same scan always
/// gives the same plan. Pinned first, then most played, then most recently played, then by name, then by
/// folder (two setups can share a name).
/// </summary>
public static class BulkRanking
{
    public static readonly IComparer<DetectedInstance> Order = Comparer<DetectedInstance>.Create(Compare);

    public static int Compare(DetectedInstance? a, DetectedInstance? b)
    {
        if (ReferenceEquals(a, b)) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        var c = b.Pinned.CompareTo(a.Pinned);
        if (c != 0) return c;
        c = (b.PlaytimeSeconds ?? 0).CompareTo(a.PlaytimeSeconds ?? 0);
        if (c != 0) return c;
        c = (b.LastPlayed ?? DateTimeOffset.MinValue).CompareTo(a.LastPlayed ?? DateTimeOffset.MinValue);
        if (c != 0) return c;
        c = StringComparer.OrdinalIgnoreCase.Compare(a.Name, b.Name);
        if (c != 0) return c;
        c = StringComparer.Ordinal.Compare(a.GameDir, b.GameDir);
        return c != 0 ? c : StringComparer.Ordinal.Compare(a.Kind.ToString(), b.Kind.ToString());
    }

    /// <summary>Minecraft versions newest first (how version groups are listed and applied).</summary>
    public static readonly IComparer<string> NewestFirst = Comparer<string>.Create((a, b) => McVersion.Compare(b, a));

    /// <summary>Minecraft versions oldest first (the one order version gates are ever taken in).</summary>
    public static readonly IComparer<string> Ascending = Comparer<string>.Create(McVersion.Compare);
}

/// <summary>The bulk import's plain rules and the sentences that go with them (decisions B3, B4, B6).</summary>
public static class BulkRules
{
    public const string UnknownVersionReason = "Origin couldn't tell which Minecraft version this setup is, so it can't come over.";

    public static string UnsupportedReason(string version) => $"Origin doesn't support Minecraft {version}, so this setup can't come over.";

    public const string ForgeNote = "Forge mods can't come over. Origin runs Fabric, except on 1.8.9.";

    // The drawer's short row captions for the same facts (row status lines carry no full stop). The sentences above
    // are what the plan, the summary and the Done page say; these are what a row says. One source each.

    /// <summary>A Forge/NeoForge setup's row caption: what happens, and why, in one line (Will asked for the reason to stay).</summary>
    public const string ForgeCaption = "Forge mods can't come over (Fabric only, except 1.8.9)";

    /// <summary>The row caption of a setup on a Minecraft version Origin doesn't have (the full sentence: <see cref="UnsupportedReason"/>).</summary>
    public const string UnsupportedCaption = "Not supported";

    /// <summary>The row caption of a setup whose version or loader is a guess (the full reason: <see cref="UncertainReason"/>).</summary>
    public const string UncertainCaption = "Not sure of the version";

    /// <summary>
    /// The one place a bulk import asks "is this setup's version a guess?": the detector's own certainty
    /// (<see cref="DetectedInstance.VersionCertain"/> and <see cref="DetectedInstance.LoaderCertain"/>, set by EvidenceJudge).
    /// </summary>
    public static bool IsVersionUncertain(DetectedInstance setup) => !setup.VersionCertain || !setup.LoaderCertain;

    /// <summary>Why the version is a guess, in plain words: the detector's evidence when it gives one.</summary>
    public static string UncertainReason(DetectedInstance setup) =>
        string.IsNullOrWhiteSpace(setup.UncertainReason)
            ? "Origin worked this out from the setup's files, not from its launcher. It comes over as this version unless you untick it."
            : setup.UncertainReason;

    /// <summary>
    /// Forge and NeoForge mods can't run on Origin's Fabric: such a setup brings only its resource and shader packs,
    /// except on 1.8.9, where Origin itself runs Forge and the setup participates fully.
    /// </summary>
    public static bool IsPacksOnly(DetectedInstance setup, string version) =>
        setup.Loader is LoaderKind.Forge or LoaderKind.NeoForge && version != "1.8.9";
}
