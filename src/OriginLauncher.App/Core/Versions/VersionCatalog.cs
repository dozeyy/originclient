namespace OriginLauncher.App.Core.Versions;

// One card in the version-selection grid: a version family + its artwork.
// Every version a card lists is playable — the picker only ever shows versions
// Origin ships (Will, 2026-10-12: no greyed "Coming Soon" entries; versions
// without an Origin build are left out entirely because they will never ship).
public sealed class VersionGroup
{
    public required string Key { get; init; }        // "1.20"
    public required string Title { get; init; }       // "1.20 – 1.20.4"
    public required string Tagline { get; init; }     // "Trails & Tales"
    public required string Description { get; init; }  // what these versions added
    public required string ImagePath { get; init; }   // "/Assets/Versions/1.20.png"
    public required IReadOnlyList<string> Versions { get; init; }  // newest first

    // Default selection when the card's side panel opens.
    public string Newest => Versions[0];
}

// The fixed set of grid groups, newest at the top-left. Lists EXACTLY the
// versions in VersionManager.OriginBuilds — tools/release/check_consistency.py
// fails CI if a version is in one and not the other, so a shipped version can't
// be missing from the picker and an unbuilt one can't appear in it. Shipping a
// new version = add it to OriginBuilds AND to its card here.
public static class VersionCatalog
{
    public static IReadOnlyList<VersionGroup> Groups { get; } = Build();

    private static VersionGroup Group(string key, string title, string tagline, string description, params string[] versions) =>
        new()
        {
            Key = key,
            Title = title,
            Tagline = tagline,
            Description = description,
            ImagePath = $"/Assets/Versions/{key}.png",
            Versions = versions,
        };

    // Grid reading order: index 0 = top-left (newest), last = bottom-right
    // (oldest). Each group lists its member versions newest-first. Descriptions
    // are the real headline features of each update era.
    //
    // Left out ON PURPOSE (Will, 2026-10-12: they will never ship, so they are
    // not shown greyed either): every 26.x release (the whole "26" card),
    // 1.20.6, 1.20.5, 1.20.3, 1.19.1, 1.19, 1.18.1, 1.17, and 1.16 – 1.16.4.
    // Pulled earlier and likewise absent: 1.21.5, 1.21.9 (2026-08-06) and the
    // Forge pair 1.12.2 / 1.8.9 (whose 1.12 and 1.8 cards went with them).
    private static IReadOnlyList<VersionGroup> Build() =>
    [
        Group("1.21", "1.21 – 1.21.11", "Tricky Trials",
            "Explore the new trial chambers and outsmart their spawners for loot from ominous vaults. Battle the wind-charging Breeze and the crossbow-wielding Bogged, forge the powerful Mace for devastating smash attacks, and automate builds with the new crafter block and expanded copper and tuff sets.",
            "1.21.11", "1.21.10", "1.21.8", "1.21.7",
            "1.21.6", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21"),
        Group("1.20", "1.20 – 1.20.4",  "Trails & Tales",
            "Wander pink cherry groves, brush suspicious sand and gravel to uncover archaeology finds, and hatch the ancient sniffer from a rediscovered egg. Ride camels across the desert, craft bamboo rafts and hanging signs, and personalize your gear with the reworked smithing table and decorative armor trims.",
            "1.20.4", "1.20.2", "1.20.1", "1.20"),
        Group("1.19", "1.19.2 – 1.19.4", "The Wild Update",
            "Descend into the pitch-black deep dark and creep through ancient cities without waking the blind, unstoppable Warden. Explore lush mangrove swamps, collect mud and froglights, and befriend the item-fetching allay in one of Minecraft's most atmospheric updates.",
            "1.19.4", "1.19.3", "1.19.2"),
        Group("1.18", "1.18.2",         "Caves & Cliffs II",
            "The update that completely reshaped the world: mountains now soar and caves plunge to a much deeper build limit. Discover glowing lush caves full of axolotls and glow berries, towering dripstone caverns, and dramatic new terrain generation across every biome.",
            "1.18.2"),
        Group("1.17", "1.17.1",         "Caves & Cliffs I",
            "The first half of the great cave overhaul introduces sparkling amethyst geodes, oxidizing copper for new builds, and a wave of new mobs — playful axolotls, mountain goats, and the ethereal glow squid — plus powder snow to sink into.",
            "1.17.1"),
        Group("1.16", "1.16.5",         "The Nether Update",
            "The Nether finally comes alive with crimson and warped forests, soul sand valleys, and basalt deltas to explore. Trade with piglins, ride striders across lava, mine ancient debris to craft top-tier netherite gear, and set your spawn with a respawn anchor.",
            "1.16.5"),
    ];

    // Every version Origin ships, newest first — the grid's versions in reading
    // order. The JVM arguments picker lists these rather than Mojang's full
    // release manifest, since args can only ever apply to a version the
    // launcher can start.
    public static IReadOnlyList<string> SupportedVersions { get; } =
        Groups.SelectMany(g => g.Versions).ToList();

    // The newest version (the first card's first entry, 1.21.11 today) — the
    // launcher's default when no valid version is saved.
    public static string DefaultVersion => SupportedVersions[0];

    // True when a version string is one Origin ships (i.e. selectable from the
    // grid). Used to drop a stale saved selection — e.g. a version that was
    // pulled — so the launcher falls back to DefaultVersion instead.
    public static bool IsSupported(string? version) =>
        version != null && SupportedVersions.Contains(version);
}
