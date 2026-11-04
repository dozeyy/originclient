using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

/// <summary>
/// Lunar plays out of the normal .minecraft (its "game directory", .minecraft by default: worlds, screenshots), so on a
/// PC that also has the official launcher the same folder used to show up twice: once as Lunar's (its per-version
/// profiles, reading .minecraft for worlds) and once as the official launcher's ".minecraft" setup. Two launchers for
/// one player's data meant Home's bring-over button could never be one click (Will, 2026-11-04: "lunar uses normal
/// minecraft ... make lunar use the original and if transferring still call it lunar cus other people might be confused").
///
/// The rule, after every detector ran (ImportEngine.ScanAsync): an official-launcher setup whose game folder IS the
/// game directory a Lunar setup plays out of becomes part of the Lunar source. It keeps its folder and everything it
/// brings (one folder is one setup: its SetupId, contents and import memory never change), it is named "Minecraft folder"
/// like the .minecraft PvpClientSource lists when no official launcher is there, and the official profile names stay as
/// its aliases. Official-launcher setups in a folder of their own (a profile with a custom game directory) are the
/// player's real official-launcher instances: they stay the Minecraft Launcher's, so nothing is lost or mislabelled.
/// A Lunar game directory that is not .minecraft folds nothing (its worlds are not .minecraft's).
/// </summary>
public static class LunarFold
{
    /// <summary>The folded setup's name ("Lunar: Minecraft folder"), the same as PvpClientSource's plain .minecraft.</summary>
    public const string FolderName = "Minecraft folder";

    public static void Apply(List<DetectedInstance> setups)
    {
        var lunarGameDirs = setups
            .Where(s => s.Kind == SourceKind.Lunar && s.SharedGameDir != null)
            .Select(s => s.SharedGameDir!)
            .ToList();
        if (lunarGameDirs.Count == 0) return;
        for (var i = 0; i < setups.Count; i++)
        {
            var setup = setups[i];
            if (setup.Kind != SourceKind.OfficialLauncher || setup.ModsDir != null) continue;
            if (!lunarGameDirs.Any(dir => SetupKey.SameFolder(dir, setup.GameDir))) continue;
            setups[i] = setup with
            {
                Kind = SourceKind.Lunar,
                Name = FolderName,
                Aliases = new[] { setup.Name }.Concat(setup.Aliases)
                    .Where(a => !string.Equals(a, FolderName, StringComparison.Ordinal))
                    .Distinct(StringComparer.Ordinal).ToList(),
            };
        }
    }
}
