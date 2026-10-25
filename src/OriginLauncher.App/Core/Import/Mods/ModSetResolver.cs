using System.IO;

using OriginLauncher.App.Core.Compat;

namespace OriginLauncher.App.Core.Import.Mods;

/// <summary>A jar found in the source's mods folder, with what it says about itself.</summary>
public sealed record SourceMod(string Path, string FileName, bool DisabledInSource, ModInfo Info);

/// <summary>What the mod set is resolved against: the target version, what Origin includes, and the Java it runs.</summary>
public sealed record ResolveContext(string TargetVersion, BuiltInModSet BuiltIns, string JavaVersion, string? LoaderVersion = null)
{
    /// <summary>1.8.9 runs on Forge in Origin: Fabric mods can't load there.</summary>
    public bool TargetIsForge189 => TargetVersion == "1.8.9";
}

// Decides, for every jar of a source's mods folder, what happens to it in the target (D12). The checking itself
// is the shared compatibility engine (Core/Compat/CompatEngine, Phase 5.1): this class only asks it about the
// source mods as import candidates and turns its findings into the import's own outcomes:
//   not a Fabric mod (or 1.8.9's Forge rule)   -> WrongLoader, listed, not copied
//   Origin already includes it (by mod id)     -> BuiltIn, "Origin includes X v", not copied
//   built for another Minecraft version        -> WrongMinecraft, listed, not copied
//   two versions of one id                     -> the kept one is imported, the rest are Duplicate
//   known not to work with Origin (D13)        -> copied DISABLED with the reason
//   dependency not met / "breaks" conflict     -> copied DISABLED with the reason
//   server-only mod on the client              -> copied DISABLED (the loader would skip it anyway)
//   disabled in the source (.jar.disabled)     -> arrives disabled, as it was
// Built-ins are checked before the Minecraft version on purpose: a player's old Sodium for the wrong version should
// still read "Origin includes Sodium", not "wrong version". 1.8.9 (Forge) keeps its old rule: after the loader /
// Minecraft check, every jar is copied as it is, because mcmod.info is advisory there. Pure: it works on
// already-read metadata.
public static class ModSetResolver
{
    /// <summary>Reads the *.jar / *.jar.disabled files at the top of <paramref name="modsDir"/> through the guard.</summary>
    public static List<SourceMod> ReadSourceMods(string modsDir, SourceFs fs, CancellationToken ct)
    {
        var mods = new List<SourceMod>();
        foreach (var path in fs.EnumerateFiles(modsDir, recursive: false).OrderBy(p => p, StringComparer.OrdinalIgnoreCase))
        {
            ct.ThrowIfCancellationRequested();
            var name = System.IO.Path.GetFileName(path);
            var disabled = name.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase);
            if (!disabled && !name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue;
            using var stream = fs.OpenRead(path);
            var info = stream == null
                ? new ModInfo { FileName = name, ReadFailed = true }
                : ModMetadataReader.Read(stream, name);
            mods.Add(new SourceMod(path, name, disabled, info));
        }
        return mods;
    }

    public static List<ModDecision> Resolve(IReadOnlyList<SourceMod> mods, ResolveContext ctx)
    {
        var entries = mods
            .Select(m => new ModFile(m.Path, m.FileName, Enabled: !m.DisabledInSource, ModRole.ImportCandidate, m.Info))
            .ToList();
        var platform = new CompatPlatform(
            ctx.TargetVersion, ctx.TargetIsForge189 ? LoaderFamily.LegacyForge : LoaderFamily.Fabric,
            ctx.LoaderVersion, ctx.JavaVersion, ctx.BuiltIns);
        var report = CompatEngine.Check(entries, platform);

        var decisions = new Dictionary<SourceMod, ModDecision>(ReferenceEqualityComparer.Instance);
        for (var i = 0; i < mods.Count; i++)
            decisions[mods[i]] = Decide(mods[i], entries[i], report, entries, mods, ctx);
        return mods.OrderBy(m => m.FileName, StringComparer.OrdinalIgnoreCase).Select(m => decisions[m]).ToList();
    }

    private static ModDecision Decide(
        SourceMod mod, ModFile entry, CompatReport report, List<ModFile> entries, IReadOnlyList<SourceMod> mods, ResolveContext ctx)
    {
        var terminal = report.Terminal(entry);
        // 1.8.9: only the loader / Minecraft rules apply; duplicates and dependencies are not judged (advisory metadata).
        if (ctx.TargetIsForge189 && terminal is { Kind: FindingKind.DuplicateId }) terminal = null;
        // Already turned off in the source: the side rule changes nothing, it simply stays as it was.
        if (mod.DisabledInSource && terminal is { Kind: FindingKind.WrongSide }) terminal = null;

        if (terminal != null)
        {
            switch (terminal.Kind)
            {
                case FindingKind.Unreadable:
                    return Info(mod, ModOutcome.Unreadable, terminal.Summary);
                case FindingKind.ForeignClient:
                case FindingKind.WrongLoader:
                    return Info(mod, ModOutcome.WrongLoader, terminal.Summary);
                case FindingKind.OriginManagedConflict:
                    return Info(mod, ModOutcome.BuiltIn, terminal.Summary);
                case FindingKind.WrongMinecraft:
                    return Info(mod, ModOutcome.WrongMinecraft, terminal.Summary);
                case FindingKind.DuplicateId:
                {
                    var kept = mods[entries.IndexOf(terminal.Related!)];
                    return Info(mod, ModOutcome.Duplicate,
                        $"Duplicate of {kept.Info.Name ?? kept.Info.Id} {Shown(kept.Info.Version)}: the newer one is imported.");
                }
                default:
                    // Known problem, unmet dependency, "breaks", wrong side: imported TURNED OFF with the reason.
                    return Copy(mod, ModOutcome.CopiedDisabled, report.ReasonFor(entry));
            }
        }

        return mod.DisabledInSource
            ? Copy(mod, ModOutcome.CopiedAsBefore, "Disabled in your old setup.")
            : Copy(mod, ModOutcome.Copied, null);
    }

    private static ModDecision Info(SourceMod mod, ModOutcome outcome, string reason) =>
        new(mod.FileName, mod.Info.Id, mod.Info.Name, mod.Info.Version, outcome, reason, null, mod.Path);

    private static ModDecision Copy(SourceMod mod, ModOutcome outcome, string? reason)
    {
        var jar = mod.FileName.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase)
            ? mod.FileName[..^".disabled".Length] : mod.FileName;
        var dest = outcome == ModOutcome.Copied ? jar : jar + ".disabled";
        return new ModDecision(mod.FileName, mod.Info.Id, mod.Info.Name, mod.Info.Version, outcome, reason, dest, mod.Path);
    }

    private static string Shown(string? version) => version == null ? "" : version.Split('+')[0];
}
