namespace OriginLauncher.App.Core.Versions;

// One selectable Minecraft version inside a group (e.g. "1.20.4"). Supported =
// Origin ships a full build for it (the mandate: only versions that get the
// complete Origin experience are playable); everything else shows as
// "Coming Soon" and can't be launched from the grid.
public sealed record VersionEntry(string Id, bool Supported);

// One card in the 3x3 version-selection grid: a version family + its artwork.
public sealed class VersionGroup
{
    public required string Key { get; init; }        // "1.20"
    public required string Title { get; init; }       // "1.20 – 1.20.6"
    public required string Tagline { get; init; }     // "Trails & Tales"
    public required string Description { get; init; }  // what these versions added
    public required string ImagePath { get; init; }   // "/Assets/Versions/1.20.png"
    public required IReadOnlyList<VersionEntry> Versions { get; init; }

    // The card is playable (clickable) only when at least one version in it has
    // an Origin build; otherwise the whole card is a disabled "Coming Soon".
    public bool AnySupported => Versions.Any(v => v.Supported);

    // Default selection when the card's side panel opens: the newest supported
    // version (Versions is authored newest-first).
    public string? NewestSupported => Versions.FirstOrDefault(v => v.Supported)?.Id;
}

// The fixed set of grid groups, newest at the top-left, oldest at the bottom-
// right. "Supported" is derived from VersionManager.OriginSupportedVersions so
// there is exactly one source of truth for which versions Origin actually ships
// — adding a version to OriginBuilds automatically un-greys it here.
public static class VersionCatalog
{
    public static IReadOnlyList<VersionGroup> Groups { get; } = Build();

    private static VersionGroup Group(string key, string title, string tagline, string description, params string[] versions)
    {
        var supported = VersionManager.OriginSupportedVersions;
        return new VersionGroup
        {
            Key = key,
            Title = title,
            Tagline = tagline,
            Description = description,
            ImagePath = $"/Assets/Versions/{key}.png",
            Versions = versions.Select(v => new VersionEntry(v, supported.Contains(v))).ToList(),
        };
    }

    // Grid reading order: index 0 = top-left (newest), index 8 = bottom-right
    // (oldest). Each group lists its member versions newest-first. Descriptions
    // are the real headline features of each update era.
    private static IReadOnlyList<VersionGroup> Build() =>
    [
        Group("26",   "26.1 – 26.2",    "The Latest Releases",
            "The newest Minecraft releases, bringing the freshest content, mobs, and blocks alongside the latest performance and stability improvements. This is the cutting edge of the game — expect the most up-to-date features and the best modern optimizations.",
            "26.2", "26.1.2", "26.1.1", "26.1"),
        Group("1.21", "1.21 – 1.21.11", "Tricky Trials",
            "Explore the new trial chambers and outsmart their spawners for loot from ominous vaults. Battle the wind-charging Breeze and the crossbow-wielding Bogged, forge the powerful Mace for devastating smash attacks, and automate builds with the new crafter block and expanded copper and tuff sets.",
            "1.21.11", "1.21.10", "1.21.8", "1.21.7",
            "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21"),
        Group("1.20", "1.20 – 1.20.6",  "Trails & Tales",
            "Wander pink cherry groves, brush suspicious sand and gravel to uncover archaeology finds, and hatch the ancient sniffer from a rediscovered egg. Ride camels across the desert, craft bamboo rafts and hanging signs, and personalize your gear with the reworked smithing table and decorative armor trims.",
            "1.20.6", "1.20.5", "1.20.4", "1.20.3", "1.20.2", "1.20.1", "1.20"),
        Group("1.19", "1.19 – 1.19.4",  "The Wild Update",
            "Descend into the pitch-black deep dark and creep through ancient cities without waking the blind, unstoppable Warden. Explore lush mangrove swamps, collect mud and froglights, and befriend the item-fetching allay in one of Minecraft's most atmospheric updates.",
            "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19"),
        Group("1.18", "1.18.1 – 1.18.2","Caves & Cliffs II",
            "The update that completely reshaped the world: mountains now soar and caves plunge to a much deeper build limit. Discover glowing lush caves full of axolotls and glow berries, towering dripstone caverns, and dramatic new terrain generation across every biome.",
            "1.18.2", "1.18.1"),
        Group("1.17", "1.17.1",         "Caves & Cliffs I",
            "The first half of the great cave overhaul introduces sparkling amethyst geodes, oxidizing copper for new builds, and a wave of new mobs — playful axolotls, mountain goats, and the ethereal glow squid — plus powder snow to sink into.",
            "1.17.1", "1.17"),
        Group("1.16", "1.16.5",         "The Nether Update",
            "The Nether finally comes alive with crimson and warped forests, soul sand valleys, and basalt deltas to explore. Trade with piglins, ride striders across lava, mine ancient debris to craft top-tier netherite gear, and set your spawn with a respawn anchor.",
            "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1", "1.16"),
        Group("1.12", "1.12.2",         "World of Color",
            "A vibrant classic packed with colorful concrete and glazed terracotta for building, chatty tamable parrots, and the menacing illagers of the woodland mansion. The new recipe book made crafting friendlier for everyone.",
            "1.12.2", "1.12.1", "1.12"),
        Group("1.8",  "1.8.9",          "The Classic",
            "The definitive old-school combat version and the enduring home of competitive Minecraft PvP. Prized for its simple, click-based fighting and rock-solid feel, 1.8.9 remains the go-to choice for hypixel-era servers and classic gameplay.",
            "1.8.9", "1.8.8", "1.8"),
    ];

    // Newest playable version overall (1.21.1 today) — the launcher's default
    // when no valid version is persisted.
    public static string? DefaultVersion =>
        Groups.SelectMany(g => g.Versions).FirstOrDefault(v => v.Supported)?.Id;

    // True when a version string is one Origin actually ships (i.e. selectable
    // from the grid) — used to reject a stale persisted selection.
    public static bool IsSupported(string? version) =>
        version != null && Groups.SelectMany(g => g.Versions).Any(v => v.Id == version && v.Supported);

    public static VersionGroup? GroupContaining(string version) =>
        Groups.FirstOrDefault(g => g.Versions.Any(v => v.Id == version));
}
