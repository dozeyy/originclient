using System.IO;

namespace OriginLauncher.App.Core.Import.Mods;

/// <summary>A jar found in the source's mods folder, with what it says about itself.</summary>
public sealed record SourceMod(string Path, string FileName, bool DisabledInSource, ModInfo Info);

/// <summary>What the mod set is resolved against: the target version, what Origin includes, and the Java it runs.</summary>
public sealed record ResolveContext(string TargetVersion, BuiltInModSet BuiltIns, string JavaVersion, string? LoaderVersion = null)
{
    /// <summary>1.8.9 runs on Forge in Origin: Fabric mods can't load there.</summary>
    public bool TargetIsForge189 => TargetVersion == "1.8.9";
}

// Decides, for every jar of a source's mods folder, what happens to it in the
// target (D12). The order matters and is the whole design:
//   1. not a Fabric mod (or 1.8.9's Forge rule)     -> listed, not copied
//   2. Origin already includes it (by mod id)       -> listed "Origin includes X v", not copied
//   3. built for another Minecraft version          -> listed, not copied
//   4. two versions of one id                       -> the higher is imported, the rest listed
//   5. known not to work with Origin (D13)          -> copied DISABLED with the reason
//   6. dependency not met / "breaks" conflict       -> copied DISABLED with the reason
//   7. disabled in the source (.jar.disabled)       -> arrives disabled, as it was
// Built-ins are checked before the Minecraft version on purpose: a player's old
// Sodium for the wrong version should still read "Origin includes Sodium", not
// "wrong version". Dependencies are checked against the mods that stay enabled
// plus the built-ins plus Minecraft, Fabric Loader and Java, repeated until
// nothing more changes (a mod disabled for a missing dependency can't satisfy
// another's). Pure: it works on already-read metadata.
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
        var decisions = new Dictionary<SourceMod, ModDecision>(ReferenceEqualityComparer.Instance);
        var candidates = new List<SourceMod>();

        foreach (var mod in mods.OrderBy(m => m.FileName, StringComparer.OrdinalIgnoreCase))
        {
            var early = Classify(mod, ctx);
            if (early != null) decisions[mod] = early;
            else candidates.Add(mod);
        }

        // 1.8.9 (Forge): the legacy-Forge jars that passed are copied as they are; no Fabric rules apply.
        if (ctx.TargetIsForge189)
        {
            foreach (var mod in candidates)
                decisions[mod] = Copy(mod, mod.DisabledInSource ? ModOutcome.CopiedAsBefore : ModOutcome.Copied,
                    mod.DisabledInSource ? "Disabled in your old setup." : null);
            return Ordered(mods, decisions);
        }

        // Duplicate ids: an enabled copy beats a disabled one, then the higher version, then the file name.
        var kept = new List<SourceMod>();
        foreach (var group in candidates.GroupBy(m => m.Info.Id!, StringComparer.Ordinal))
        {
            var ordered = group
                .OrderBy(m => m.DisabledInSource ? 1 : 0)
                .ThenByDescending(m => m.Info.Version, Comparer<string?>.Create(FabricVersion.Compare))
                .ThenBy(m => m.FileName, StringComparer.OrdinalIgnoreCase)
                .ToList();
            kept.Add(ordered[0]);
            foreach (var other in ordered.Skip(1))
                decisions[other] = Info(other, ModOutcome.Duplicate,
                    $"Duplicate of {ordered[0].Info.Name ?? ordered[0].Info.Id} {Shown(ordered[0].Info.Version)}: the newer one is imported.");
        }

        var disabledBy = new Dictionary<SourceMod, string>(ReferenceEqualityComparer.Instance);
        foreach (var mod in kept)
            if (KnownIncompatible.Check(mod.Info, ctx.BuiltIns) is { } known) disabledBy[mod] = known;

        // Dependency / breaks checks until stable.
        bool changed;
        do
        {
            changed = false;
            var environment = BuildEnvironment(kept.Where(m => !m.DisabledInSource && !disabledBy.ContainsKey(m)));
            foreach (var mod in kept)
            {
                if (mod.DisabledInSource || disabledBy.ContainsKey(mod)) continue;
                var problem = FindProblem(mod.Info, environment, ctx);
                if (problem == null) continue;
                disabledBy[mod] = problem;
                changed = true;
            }
        } while (changed);

        foreach (var mod in kept)
        {
            if (mod.DisabledInSource)
                decisions[mod] = Copy(mod, ModOutcome.CopiedAsBefore, "Disabled in your old setup.");
            else if (disabledBy.TryGetValue(mod, out var reason))
                decisions[mod] = Copy(mod, ModOutcome.CopiedDisabled, reason);
            else
                decisions[mod] = Copy(mod, ModOutcome.Copied, null);
        }
        return Ordered(mods, decisions);
    }

    private static List<ModDecision> Ordered(IReadOnlyList<SourceMod> mods, Dictionary<SourceMod, ModDecision> decisions) =>
        mods.OrderBy(m => m.FileName, StringComparer.OrdinalIgnoreCase).Select(m => decisions[m]).ToList();

    // Steps 1-3: the reasons a jar is not copied at all. Null = it goes on to duplicate/dependency checks.
    private static ModDecision? Classify(SourceMod mod, ResolveContext ctx)
    {
        var info = mod.Info;
        if (info.ReadFailed)
            return Info(mod, ModOutcome.Unreadable, "Couldn't read this file.");

        // Another client's own core jar (Feather, Lunar, Badlion, LabyMod) can never load here: it needs libraries only
        // its own launcher ships and hooks every class, so it would stop the game at the first one (2026-10-20).
        if (ClientCoreMods.Find(info.Id, info.FileName, info.Provides) is { } clientCore)
            return Info(mod, ModOutcome.WrongLoader, clientCore.Reason);

        if (ctx.TargetIsForge189)
        {
            if (info.Loader is ModLoader.Fabric or ModLoader.Quilt)
                return Info(mod, ModOutcome.WrongLoader, "1.8.9 runs on Forge in Origin, so Fabric mods can't load there.");
            if (info.Loader == ModLoader.LegacyForge)
                return info.McVersion != null && info.McVersion != "1.8.9"
                    ? Info(mod, ModOutcome.WrongMinecraft, $"Built for Minecraft {info.McVersion}, not 1.8.9.")
                    : null;
            return Info(mod, ModOutcome.WrongLoader, "Not a Forge 1.8.9 mod.");
        }

        if (info.Loader != ModLoader.Fabric)
        {
            if (KnownIncompatible.Check(info, ctx.BuiltIns) is { } known)
                return Info(mod, ModOutcome.WrongLoader, known);
            return Info(mod, ModOutcome.WrongLoader, info.Loader switch
            {
                ModLoader.Forge or ModLoader.LegacyForge => "This is a Forge mod. Origin runs Fabric, so it can't load it.",
                ModLoader.NeoForge => "This is a NeoForge mod. Origin runs Fabric, so it can't load it.",
                ModLoader.Quilt => "This is a Quilt-only mod. Origin runs Fabric, so it can't load it.",
                _ => "Not a Fabric mod (no mod information found in it).",
            });
        }

        if (info.Id == null)
            return Info(mod, ModOutcome.Unreadable, "Couldn't read this mod's details.");

        var builtIn = ctx.BuiltIns.Find(info.Id) ?? info.Provides.Select(ctx.BuiltIns.Find).FirstOrDefault(b => b != null);
        if (builtIn != null)
            return Info(mod, ModOutcome.BuiltIn, builtIn.Describe() + ".");

        if (info.MinecraftRange is { } range && !range.IsSatisfiedBy(ctx.TargetVersion))
            return Info(mod, ModOutcome.WrongMinecraft, $"Built for Minecraft {range.Display}, not {ctx.TargetVersion}.");
        return null;
    }

    private sealed class ProvidedIds
    {
        public readonly Dictionary<string, string?> Versions = new(StringComparer.Ordinal);
    }

    // Every id an enabled imported mod answers to (its own, what it provides, what it nests) with its version.
    private static ProvidedIds BuildEnvironment(IEnumerable<SourceMod> enabled)
    {
        var env = new ProvidedIds();
        foreach (var mod in enabled)
        {
            env.Versions.TryAdd(mod.Info.Id!, mod.Info.Version);
            foreach (var provided in mod.Info.Provides) env.Versions.TryAdd(provided, mod.Info.Version);
            foreach (var nested in mod.Info.Nested) env.Versions.TryAdd(nested.Id, nested.Version);
        }
        return env;
    }

    private static bool TryProvide(string id, ProvidedIds env, ResolveContext ctx, out string? version)
    {
        version = null;
        switch (id)
        {
            case "minecraft": version = ctx.TargetVersion; return true;
            case "java": version = ctx.JavaVersion; return true;
            case "fabricloader": version = ctx.LoaderVersion; return true; // newest at install time: unknown, satisfies any range
        }
        if (ctx.BuiltIns.Find(id) is { } built) { version = built.Version; return true; }
        return env.Versions.TryGetValue(id, out version);
    }

    private static string? FindProblem(ModInfo info, ProvidedIds env, ResolveContext ctx)
    {
        var missing = new List<string>();
        foreach (var dep in info.Depends.OrderBy(d => d.Id, StringComparer.Ordinal))
        {
            if (dep.Id == "minecraft") continue; // already checked against the target
            var predicate = dep.Predicate;
            if (TryProvide(dep.Id, env, ctx, out var version) && (version == null || predicate.IsSatisfiedBy(version)))
                continue;
            missing.Add(predicate.MatchesAnything ? $"Needs {dep.Id}" : $"Needs {dep.Id} {predicate.Display}");
        }
        if (missing.Count > 0) return string.Join(", ", missing) + ".";

        foreach (var brk in info.Breaks.OrderBy(d => d.Id, StringComparer.Ordinal))
        {
            if (!TryProvide(brk.Id, env, ctx, out var version)) continue;
            if (version == null || brk.Predicate.IsSatisfiedBy(version))
                return $"Conflicts with {brk.Id}{(version == null ? "" : " " + version.Split('+')[0])}.";
        }
        return null;
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
