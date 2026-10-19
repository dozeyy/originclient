namespace OriginLauncher.App.Core.Import.Mods;

// A small, data-only table of mods that are known not to work with what Origin
// ships. Kept to cases that are certain and that a dependency check cannot see
// (the mods do not declare the conflict). Add a row, not code.
public static class KnownIncompatible
{
    private sealed record Rule(string Id, string? FileNamePrefix, string? MaxVersion, string? NeedsBuiltIn, string? BuiltInAtLeast, string Reason);

    private static readonly Rule[] Rules =
    {
        // OptiFine replaces the renderer Origin builds on; OptiFabric only exists to load it.
        new("optifabric", "optifabric", null, null, null,
            "OptiFabric doesn't work with Origin: Origin uses Sodium + Iris."),
        new("optifine", "optifine", null, null, null,
            "OptiFine doesn't work with Origin: Origin uses Sodium + Iris."),
        // Distant Horizons 2.3.2 and older crash outright on Sodium 0.8+ (which Origin includes).
        new("distanthorizons", null, "2.3.2", "sodium", "0.8.0",
            "Distant Horizons 2.3.2 and older crashes the game with Sodium 0.8 or newer, which Origin includes. Update it first."),
    };

    /// <summary>The plain reason this mod can't be used with Origin, or null when it is not on the list.</summary>
    public static string? Check(ModInfo mod, BuiltInModSet builtIns)
    {
        foreach (var rule in Rules)
        {
            var byId = mod.Id != null && string.Equals(mod.Id, rule.Id, StringComparison.Ordinal);
            var byName = rule.FileNamePrefix != null
                && mod.FileName.StartsWith(rule.FileNamePrefix, StringComparison.OrdinalIgnoreCase);
            if (!byId && !byName) continue;

            if (rule.MaxVersion != null)
            {
                if (!FabricVersion.TryParse(mod.Version, out var version) || !FabricVersion.TryParse(rule.MaxVersion, out var max)
                    || !CoreAtMost(version, max)) continue;
            }
            if (rule.NeedsBuiltIn != null)
            {
                var built = builtIns.Find(rule.NeedsBuiltIn);
                if (built == null) continue;
                if (rule.BuiltInAtLeast != null && built.Version != null
                    && FabricVersion.Compare(built.Version, rule.BuiltInAtLeast) < 0) continue;
            }
            return rule.Reason;
        }
        return null;
    }

    // "2.3.2-b" counts as 2.3.2: the rule is about the number, whatever pre-release tag a build carries.
    private static bool CoreAtMost(FabricVersion version, FabricVersion max)
    {
        for (var i = 0; i < Math.Max(version.Core.Count, max.Core.Count); i++)
        {
            var a = i < version.Core.Count ? version.Core[i] : 0;
            var b = i < max.Core.Count ? max.Core[i] : 0;
            if (a != b) return a < b;
        }
        return true;
    }
}
