using System.IO;

namespace OriginLauncher.App.Core.Loaders;

// FML's boot splash (1.8.9, the earliest thing a player sees) is configured by
// <gameDir>/config/splash.properties, i.e. the ACCOUNT's own game folder. Seeded
// to the Origin palette so even the Forge load screen is charcoal + white
// instead of Forge's default white + red.
//
// The keys are Forge 11.15.1.2318's own, read from SplashProgress's bytecode
// (javap, 2026-10-19): enabled, rotate, logoOffset, background, font, barBorder,
// bar, barBackground. The first seed (until 2026-10-19) wrote backgroundColor/
// fontColor/barColor/... which FML never reads, so every player got the default
// red splash -- dev-shots' filmed 1.8.9 run showed it. Values go through
// Integer.decode, so "0x" hex is right.
//
// The file is player-ownable config: written when absent, and replaced only when
// it is byte-for-byte that broken first seed (never a player's own edit).
public static class ForgeSplashTheme
{
    public const string Content =
        "enabled=true\n" +
        "background=0x050505\n" +
        "font=0xF5F5F5\n" +
        "bar=0xE0E0E0\n" +
        "barBorder=0x3A3A3A\n" +
        "barBackground=0x161616\n";

    // The first seed, exactly as it was written (wrong keys).
    internal const string BrokenFirstSeed =
        "enabled=true\n" +
        "showMemory=false\n" +
        "backgroundColor=0x050505\n" +
        "fontColor=0xF5F5F5\n" +
        "barColor=0xE0E0E0\n" +
        "barBorderColor=0x3A3A3A\n" +
        "barBackgroundColor=0x161616\n";

    /// <summary>Seeds (or repairs the broken first seed of) config/splash.properties. Never throws.</summary>
    public static void Seed(string configFolder)
    {
        try
        {
            var path = Path.Combine(configFolder, "splash.properties");
            if (File.Exists(path) && File.ReadAllText(path).Replace("\r\n", "\n") != BrokenFirstSeed) return;
            Directory.CreateDirectory(configFolder);
            File.WriteAllText(path, Content);
        }
        catch
        {
            // Cosmetic only -- never block a launch over it.
        }
    }
}
