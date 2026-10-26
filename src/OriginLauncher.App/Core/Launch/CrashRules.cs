namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// The ordered crash rules (architecture audit C2). Each rule is a pure predicate over <see cref="CrashFacts"/> that
/// returns a <see cref="CrashFinding"/> or null. Every rule runs; the first finding at Strong or better (in rule order)
/// is shown, otherwise the first Possible one, otherwise Unknown. The rest go under Technical details.
///
/// Confidence comes from the KIND of evidence, never a score:
///   confirmed = the loader or the JVM names the cause in a structured message;
///   strong    = a mixin apply failure in ONE mod's config, ONE "Suspected Mods" entry that is in the launch set, a
///               known other-client core in the failing stack;
///   possible  = a stack-package match, a native frame in a GPU driver, a native exit code;
///   unknown   = nothing recognisable.
/// A fix (the window's button) is only ever built at strong or confirmed, and only of a reversible kind.
/// Adding a cause = one method + one line in <see cref="All"/> + a corpus folder with a positive and a negative case.
/// </summary>
public static class CrashRules
{
    public sealed record Rule(string Name, Func<CrashFacts, CrashFinding?> Run);

    /// <summary>In priority order: the JVM before memory before files before mods before guesses.</summary>
    public static readonly IReadOnlyList<Rule> All =
    [
        new("jvm-start", JvmStart),
        new("out-of-memory", OutOfMemory),
        new("java-version", JavaVersion),
        new("damaged-file", DamagedFile),
        new("fabric-dependency", FabricDependency),
        new("duplicate-mod", DuplicateMod),
        new("forge-missing", ForgeMissing),
        new("mixin-apply", MixinApply),
        new("forge-errored", ForgeErrored),
        new("client-core", ClientCore),
        new("suspected-mod", SuspectedMod),
        new("deepest-cause", DeepestCause),
        new("stack-packages", StackPackages),
        new("graphics-driver", GraphicsDriver),
        new("native-crash", NativeCrash),
    ];

    /// <summary>Every rule's finding, in rule order (a rule that throws is skipped: a diagnosis must never crash).</summary>
    public static List<CrashFinding> Evaluate(CrashFacts f)
    {
        var found = new List<CrashFinding>();
        foreach (var rule in All)
        {
            try
            {
                if (rule.Run(f) is { } finding) found.Add(finding);
            }
            catch
            {
                /* one broken rule must not take the crash window down with it */
            }
        }
        return found;
    }

    /// <summary>The finding to show: first Strong-or-better, else first Possible, else Unknown.</summary>
    public static CrashFinding Pick(IReadOnlyList<CrashFinding> found) =>
        found.FirstOrDefault(x => x.Confidence >= CrashConfidence.Strong)
        ?? found.FirstOrDefault(x => x.Confidence == CrashConfidence.Possible)
        ?? Unknown();

    public static CrashFinding Unknown() => new("unknown", CrashCause.Unknown, CrashConfidence.Unknown, [],
        "UNKNOWN", "Minecraft closed unexpectedly",
        "Minecraft stopped, and its logs don't say why in a way Origin can read.",
        "Not known. Mods you added are the most common cause.",
        "Press Play to try again. If it happens again, try once without your added mods, or use Copy report and share it when you ask for help.",
        null, []);

    // ---- the rules ---------------------------------------------------------------------------------------------

    private static CrashFinding? JvmStart(CrashFacts f)
    {
        if (f.HeapReserveFailed)
            return new("jvm-start", CrashCause.JavaProblem, CrashConfidence.Confirmed, [],
                "JAVA", "Java couldn't set aside Minecraft's memory",
                "Java stopped before Minecraft started: it couldn't reserve the memory Minecraft is set to use.",
                $"The memory setting ({Gb(f.RamMb)}) is more than Windows could give Java right now.",
                "Lower Memory in Settings, or close other programs, then press Play.",
                null, Lines(f.JvmStartLine));
        if (f.UnrecognizedOption is { } opt)
            return new("jvm-start", CrashCause.JavaProblem, CrashConfidence.Confirmed, [],
                "JAVA", "Java didn't accept a launch option",
                "Java stopped before Minecraft started.",
                $"Java doesn't know the option \"{opt}\".",
                "Remove it from Settings, Java arguments, then press Play.",
                null, Lines(f.JvmStartLine));
        if (f.JvmStartLine != null)
            return new("jvm-start", CrashCause.JavaProblem, CrashConfidence.Confirmed, [],
                "JAVA", "Minecraft's Java wouldn't start",
                "Java stopped before Minecraft started.",
                "Java refused to start the game: usually a Java argument it doesn't accept, or a Java install that is missing or broken.",
                "Check Settings, Java arguments. If they are empty, press Play again: Origin re-checks its Java on the next launch.",
                null, Lines(f.JvmStartLine));
        return null;
    }

    private static CrashFinding? OutOfMemory(CrashFacts f)
    {
        if (f.NativeOomLine != null)
            return new("out-of-memory", CrashCause.OutOfMemory, CrashConfidence.Confirmed, [],
                "OUT OF MEMORY", "Your PC ran out of memory",
                "Windows couldn't give Java the memory it asked for, so Minecraft stopped.",
                "The whole PC was out of memory (not just Minecraft's share).",
                "Close other programs. If it keeps happening, lower Memory in Settings a little.",
                null, Lines(f.NativeOomLine, f.ProblematicFrameLine));
        if (f.HeapOomLine == null) return null;

        var next = MemoryBump.Next(f.RamMb, f.TotalPhysicalMb);
        return new("out-of-memory", CrashCause.OutOfMemory, CrashConfidence.Confirmed, [],
            "OUT OF MEMORY", "Minecraft ran out of memory",
            "Java reported that Minecraft used all the memory it was given.",
            $"Minecraft needed more than the {Gb(f.RamMb)} it was given.",
            next is int up
                ? $"Raise Memory to {Gb(up)}. Origin can do it for you; you can change it back in Settings."
                : "That is already the most this PC can safely spare. Closing other programs, or removing some mods, will help.",
            next is int ram ? new CrashFix(CrashFixKind.RaiseMemory, $"Raise memory to {Gb(ram)} and relaunch", [], ram) : null,
            Lines(f.HeapOomLine));
    }

    private static CrashFinding? JavaVersion(CrashFacts f)
    {
        if (f.ClassVersionLine == null) return null;
        var owner = f.ClassVersionClass != null ? OwnerOf(f, f.ClassVersionClass) : null;
        var culprits = owner != null ? new[] { owner } : Array.Empty<CrashCulprit>();
        return new("java-version", CrashCause.JavaProblem, CrashConfidence.Confirmed, culprits,
            "JAVA", "Something needs a newer Java",
            "Java refused to load part of the game because it was built for a newer Java.",
            owner != null
                ? $"{Pretty(owner.ModId)} was built for a newer Java than this Minecraft version uses (Java {f.JavaMajor?.ToString() ?? "?"})."
                : $"A file was built for a newer Java than this Minecraft version uses (Java {f.JavaMajor?.ToString() ?? "?"}).",
            owner is { IsExternal: true }
                ? "Get the version of that mod made for this Minecraft version, or turn it off on the Mods tab."
                : "Update Origin, then press Play.",
            null, Lines(f.ClassVersionLine));
    }

    private static CrashFinding? DamagedFile(CrashFacts f)
    {
        if (f.DamagedLine == null) return null;
        var entry = f.DamagedJar != null ? Find(f, jar: f.DamagedJar) : null;
        var tech = Lines(f.DamagedLine);

        if (entry is { IsExternal: true, JarFileName: { } jar })
        {
            var who = new CrashCulprit(entry.ModId.Length > 0 ? entry.ModId : jar, jar, true);
            return new("damaged-file", CrashCause.DamagedFiles, CrashConfidence.Confirmed, [who],
                "DAMAGED FILE", "A mod file is damaged",
                "Minecraft couldn't open one of your mod files.",
                $"{jar} is broken or didn't finish downloading.",
                "Turn it off now, then download it again from where you got it.",
                new CrashFix(CrashFixKind.DisableMods, $"Turn off {Shorten(jar, 24)} and relaunch", [jar]), tech);
        }
        // Origin's own file, part of the game install, or a file the line doesn't name: re-checking every file is
        // safe (it only re-downloads what is broken).
        var named = f.DamagedJar != null && (entry != null || f.DamagedGameFiles);
        return new("damaged-file", CrashCause.DamagedFiles, named ? CrashConfidence.Confirmed : CrashConfidence.Strong,
            [], "DAMAGED FILES", "Some game files look damaged",
            "Minecraft couldn't open one of its own files.",
            f.DamagedJar != null ? $"{f.DamagedJar} is broken or didn't finish downloading." : "A game file is broken or didn't finish downloading.",
            "Let Origin check every game file and fix what it finds, then play.",
            new CrashFix(CrashFixKind.RepairFiles, "Repair files and relaunch", []), tech);
    }

    private static CrashFinding? FabricDependency(CrashFacts f)
    {
        if (f.Dependencies.Count == 0) return null;
        var subjects = f.Dependencies.Select(d => d.ModId).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var culprits = subjects.Select(id => Culprit(f, id)).ToList();
        var names = f.Dependencies.GroupBy(d => d.ModId).ToDictionary(g => g.Key, g => Name(g.First()));
        var first = f.Dependencies[0];

        var cause = string.Join(" ", f.Dependencies.Take(3).Select(d => d.Kind switch
        {
            DependencyKind.Missing => $"{Name(d)} needs {d.Target}, which isn't installed.",
            DependencyKind.WrongVersion when d.Target == "minecraft" => $"{Name(d)} is made for a different Minecraft version ({d.Needs}).",
            DependencyKind.WrongVersion => $"{Name(d)} needs {d.Needs} of {d.Target}, and a different version is installed.",
            DependencyKind.Incompatible => $"{Name(d)} doesn't work together with {d.Target}.",
            _ => $"{Name(d)} needs {d.Target}, which can't load here.",
        }));
        var fix = DisableFix(culprits, names);
        var recommended = first.Kind switch
        {
            DependencyKind.Missing => $"Install {first.Target} for this Minecraft version, or turn {Name(first)} off.",
            DependencyKind.WrongVersion when first.Target == "minecraft" => $"Turn {Name(first)} off, or get its version made for this Minecraft.",
            _ => $"Turn {Name(first)} off, or update it.",
        };
        if (culprits.Any(c => !c.IsExternal)) recommended = "Update Origin, then press Play. " + recommended;
        return new("fabric-dependency", CrashCause.ModProblem, CrashConfidence.Confirmed, culprits,
            "MOD PROBLEM", subjects.Count == 1 ? $"A mod can't run here: {Name(first)}" : "Some mods can't run here",
            "Fabric checked your mods before starting and refused to start Minecraft.",
            cause, recommended, fix, f.Dependencies.Select(d => d.Line).ToList());
    }

    private static CrashFinding? DuplicateMod(CrashFacts f)
    {
        if (f.Duplicates.Count == 0) return null;
        var dup = f.Duplicates[0];
        var jars = dup.Jars.Count > 0
            ? dup.Jars
            : f.Mods.Where(m => m.ModId.Equals(dup.ModId, StringComparison.OrdinalIgnoreCase) && m.JarFileName != null)
                .Select(m => m.JarFileName!).ToList();
        var copies = jars.Select(j => Find(f, jar: j) ?? new CrashModEntry(dup.ModId, j, true)).ToList();
        // Keep one copy: Origin's own when there is one, else the last by name (version numbers sort that way).
        var keep = copies.FirstOrDefault(c => !c.IsExternal)
                   ?? copies.OrderBy(c => c.JarFileName, StringComparer.OrdinalIgnoreCase).LastOrDefault();
        var off = copies.Where(c => c.IsExternal && !ReferenceEquals(c, keep) && c.JarFileName != null)
            .Select(c => c.JarFileName!).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var fix = off.Count > 0
            ? new CrashFix(CrashFixKind.DisableMods, off.Count == 1 ? $"Turn off the extra copy and relaunch" : $"Turn off {off.Count} extra copies and relaunch", off)
            : null;
        return new("duplicate-mod", CrashCause.ModProblem, CrashConfidence.Confirmed,
            copies.Select(c => new CrashCulprit(dup.ModId, c.JarFileName, c.IsExternal)).ToList(),
            "MOD PROBLEM", $"Two copies of {Pretty(dup.ModId)} are installed",
            "Minecraft refused to start because more than one file says it is the same mod.",
            jars.Count > 0 ? $"{string.Join(" and ", jars.Take(3))} are the same mod." : $"More than one file is the mod {dup.ModId}.",
            off.Count > 0 ? $"Keep {keep?.JarFileName ?? "one copy"} and turn the other copy off." : "Remove the extra copy on the Mods tab.",
            fix, Lines(dup.Line));
    }

    private static CrashFinding? ForgeMissing(CrashFacts f)
    {
        if (f.ForgeMissing.Count == 0) return null;
        var culprits = f.ForgeMissing.Select(m => Culprit(f, m.ModId)).ToList();
        return new("forge-missing", CrashCause.ModProblem, CrashConfidence.Confirmed, culprits,
            "MOD PROBLEM", "A mod is missing something it needs",
            "Forge refused to start Minecraft because a mod needs another mod that isn't installed.",
            string.Join(" ", f.ForgeMissing.Take(3).Select(m => m.Line)),
            "Install what it asks for, or turn it off.",
            DisableFix(culprits, null), f.ForgeMissing.Select(m => m.Line).ToList());
    }

    private static CrashFinding? MixinApply(CrashFacts f)
    {
        if (f.MixinFailures.Count == 0) return null;
        var ids = f.MixinFailures.Where(m => m.ModId != null).Select(m => m.ModId!).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var tech = f.MixinFailures.Select(m => m.Line).Take(6).ToList();
        if (ids.Count == 1)
        {
            var c = Culprit(f, ids[0]);
            return new("mixin-apply", CrashCause.ModProblem, CrashConfidence.Strong, [c],
                c.IsExternal ? "MOD PROBLEM" : "ORIGIN MOD",
                c.IsExternal ? $"A mod didn't load: {Pretty(c.ModId)}" : "One of Origin's built-in mods failed",
                "A mod failed to hook into Minecraft while it was loading, and Minecraft stopped.",
                c.IsExternal
                    ? $"{Pretty(c.ModId)} doesn't fit this Minecraft version or another mod changed the same code."
                    : $"{Pretty(c.ModId)} is part of Origin, not something you added.",
                c.IsExternal ? "Turn it off, or get its version made for this Minecraft." : "Update Origin, then press Play.",
                DisableFix([c], null), tech);
        }
        return new("mixin-apply", CrashCause.ModProblem, CrashConfidence.Possible, ids.Select(id => Culprit(f, id)).ToList(),
            "MOD PROBLEM", "Some mods failed while loading",
            "Mods failed to hook into Minecraft while it was loading.",
            ids.Count > 1 ? $"Several mods are named: {string.Join(", ", ids.Take(3).Select(Pretty))}." : "The failing mod isn't named.",
            "Try once without your added mods to see which one it is.", null, tech);
    }

    private static CrashFinding? ForgeErrored(CrashFacts f)
    {
        if (f.ForgeErrored.Count == 0) return null;
        var culprits = f.ForgeErrored.Select(e => Culprit(f, e.ModId, e.Jar)).ToList();
        var tech = f.ForgeErrored.Select(e => e.Line).ToList();
        if (culprits.Count == 1)
            return new("forge-errored", CrashCause.ModProblem, CrashConfidence.Strong, culprits,
                "MOD PROBLEM", $"A mod didn't load: {Pretty(culprits[0].ModId)}",
                "Forge stopped loading because a mod failed.",
                $"Forge marked {Pretty(culprits[0].ModId)} as errored.",
                culprits[0].IsExternal ? "Turn it off, or update it." : "Update Origin, then press Play.",
                DisableFix(culprits, null), tech);
        return new("forge-errored", CrashCause.ModProblem, CrashConfidence.Possible, culprits,
            "MOD PROBLEM", "Some mods didn't load",
            "Forge stopped loading because mods failed.",
            $"Forge marked several mods as errored: {string.Join(", ", culprits.Take(3).Select(c => Pretty(c.ModId)))}.",
            "Try once without your added mods to see which one it is.", null, tech);
    }

    private static CrashFinding? ClientCore(CrashFacts f)
    {
        foreach (var block in f.CauseChain)
        {
            var core = block.Classes.Select(ClientCoreMods.FindByClassName).FirstOrDefault(c => c != null);
            if (core == null) continue;
            var entry = f.Mods.FirstOrDefault(m => m.ModId.Equals(core.ModId, StringComparison.OrdinalIgnoreCase)
                || (m.JarFileName != null && ClientCoreMods.Find(m.ModId, m.JarFileName) == core));
            var c = new CrashCulprit(core.ModId, entry?.JarFileName, true);
            return new("client-core", CrashCause.ModProblem, CrashConfidence.Strong, [c],
                "MOD PROBLEM", $"A mod didn't load: {core.Name}",
                "Another launcher's own client files were in your mods and stopped the game.",
                $"{core.Name}'s own client files can't run inside Origin.",
                $"Turn it off. {core.Name}'s features only work inside {core.Name}.",
                DisableFix([c], null), Lines(block.Header));
        }
        return null;
    }

    private static CrashFinding? SuspectedMod(CrashFacts f)
    {
        if (f.SuspectedMods.Count == 0) return null;
        var inSet = f.SuspectedMods.Where(id => Find(f, id) != null).ToList();
        if (f.SuspectedMods.Count == 1 && inSet.Count == 1)
        {
            var c = Culprit(f, inSet[0]);
            return new("suspected-mod", CrashCause.ModProblem, CrashConfidence.Strong, [c],
                c.IsExternal ? "MOD PROBLEM" : "ORIGIN MOD",
                c.IsExternal ? $"A mod crashed the game: {Pretty(c.ModId)}" : "One of Origin's built-in mods failed",
                f.CrashDescription != null ? $"Minecraft crashed ({f.CrashDescription})." : "Minecraft crashed.",
                $"Minecraft's crash report names {Pretty(c.ModId)}.",
                c.IsExternal ? "Turn it off, or update it." : "Update Origin, then press Play.",
                DisableFix([c], null), Lines(f.SuspectedLine));
        }
        return new("suspected-mod", CrashCause.ModProblem, CrashConfidence.Possible, f.SuspectedMods.Select(id => Culprit(f, id)).ToList(),
            "MOD PROBLEM", "A mod may have crashed the game",
            f.CrashDescription != null ? $"Minecraft crashed ({f.CrashDescription})." : "Minecraft crashed.",
            $"Minecraft's crash report suspects {string.Join(", ", f.SuspectedMods.Take(3).Select(Pretty))}.",
            "Try once without your added mods to see which one it is.", null, Lines(f.SuspectedLine));
    }

    private static CrashFinding? DeepestCause(CrashFacts f)
    {
        foreach (var block in f.CauseChain)
        {
            var owners = block.Classes
                .Where(c => !CrashFactsParser.NotModCode.Any(p => c.StartsWith(p, StringComparison.Ordinal)))
                .Select(c => OwnerOf(f, c)).Where(o => o != null).Select(o => o!.ModId)
                .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            if (owners.Count > 1) return null; // several mods in one block: not clear enough
            if (owners.Count == 0) continue;
            var c = Culprit(f, owners[0]);
            return new("deepest-cause", CrashCause.ModProblem, CrashConfidence.Possible, [c],
                "MOD PROBLEM", $"A mod may have crashed the game: {Pretty(c.ModId)}",
                "Minecraft stopped with an error.",
                $"The error happened inside {Pretty(c.ModId)}'s own code.",
                "Try once without your added mods. If the game starts, turn that mod off on the Mods tab.",
                null, Lines(block.Header));
        }
        return null;
    }

    private static CrashFinding? StackPackages(CrashFacts f)
    {
        var classes = f.CauseChain.SelectMany(b => b.Classes)
            .Where(c => !CrashFactsParser.NotModCode.Any(p => c.StartsWith(p, StringComparison.Ordinal)));
        var owners = classes.Select(c => OwnerOf(f, c)).Where(o => o != null).Select(o => o!.ModId)
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        if (owners.Count != 1) return null;
        var c = Culprit(f, owners[0]);
        return new("stack-packages", CrashCause.ModProblem, CrashConfidence.Possible, [c],
            "MOD PROBLEM", $"A mod may have crashed the game: {Pretty(c.ModId)}",
            "Minecraft stopped with an error.",
            $"{Pretty(c.ModId)}'s code appears in the error.",
            "Try once without your added mods. If the game starts, turn that mod off on the Mods tab.",
            null, Lines(f.CauseChain.FirstOrDefault()?.Header));
    }

    private static readonly string[] DriverModules =
    [
        "nvoglv64", "nvwgf2um", "nvlddmkm", "atio6axx", "atioglxx", "aticfx64", "amdxx64", "amdvlk64", "ig", "libnvidia", "dri",
    ];

    private static CrashFinding? GraphicsDriver(CrashFacts f)
    {
        var driverFrame = f.ProblematicModule is { } mod && IsDriver(mod);
        if (f.GraphicsLine == null && !driverFrame) return null;
        return new("graphics-driver", CrashCause.GraphicsDriver, CrashConfidence.Possible, [],
            "GRAPHICS DRIVER", "Your graphics driver may have stopped the game",
            driverFrame ? $"Minecraft crashed inside {f.ProblematicModule}, part of your graphics driver." : "The game couldn't draw with your graphics card.",
            "The graphics driver, or overlay software (screen recorders, RGB tools) hooked into it.",
            "Update your graphics driver (NVIDIA, AMD or Intel, or Windows Update), close overlay apps, then press Play.",
            null, Lines(f.GraphicsLine, f.ProblematicFrameLine));
    }

    private static bool IsDriver(string module)
    {
        var m = module.ToLowerInvariant();
        if (System.Text.RegularExpressions.Regex.IsMatch(m, @"^ig[0-9a-z]{1,8}icd(32|64)")) return true;
        return DriverModules.Where(d => d != "ig" && d != "dri").Any(d => m.StartsWith(d, StringComparison.Ordinal))
               || m.EndsWith("_dri.so", StringComparison.Ordinal);
    }

    private static CrashFinding? NativeCrash(CrashFacts f)
    {
        if (!f.HasHsErr && !f.NativeExitCode) return null;
        return new("native-crash", CrashCause.NativeCrash, CrashConfidence.Possible, [],
            "WINDOWS CLOSED IT", "Minecraft crashed below Java",
            f.HasHsErr ? "Java itself crashed and wrote a crash dump." : $"Windows ended Minecraft (exit code 0x{f.ExitCode:X8}).",
            f.ProblematicModule != null ? $"It stopped inside {f.ProblematicModule}." : "Usually a graphics driver, overlay software or a mod with native code.",
            "Update your graphics driver and close overlay apps, then press Play. If it repeats, use Copy report when you ask for help.",
            null, Lines(f.ProblematicFrameLine));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static CrashModEntry? Find(CrashFacts f, string? id = null, string? jar = null) =>
        jar != null
            ? f.Mods.FirstOrDefault(m => string.Equals(m.JarFileName, jar, StringComparison.OrdinalIgnoreCase))
            : f.Mods.FirstOrDefault(m => m.ModId.Length > 0 && m.ModId.Equals(id, StringComparison.OrdinalIgnoreCase));

    // A mod not in the launch set is treated as added (its file can't be named, so it can never be turned off).
    private static CrashCulprit Culprit(CrashFacts f, string id, string? jarHint = null)
    {
        var entry = (jarHint != null ? Find(f, jar: jarHint) : null) ?? Find(f, id);
        return new CrashCulprit(id, entry?.JarFileName ?? null, entry?.IsExternal ?? true);
    }

    // The added mod whose code packages contain this class, or null.
    private static CrashCulprit? OwnerOf(CrashFacts f, string className)
    {
        foreach (var m in f.Mods)
        {
            if (!m.IsExternal || m.Packages == null || m.ModId.Length == 0) continue;
            if (m.Packages.Any(p => className.StartsWith(p + ".", StringComparison.Ordinal) || className == p))
                return new CrashCulprit(m.ModId, m.JarFileName, true);
        }
        return null;
    }

    // Turn the named added mods off. Null (no fix) when any culprit is Origin's own or has no known jar: half a fix
    // that leaves the cause in place would only crash again.
    private static CrashFix? DisableFix(IReadOnlyList<CrashCulprit> culprits, IReadOnlyDictionary<string, string>? names)
    {
        if (culprits.Count == 0 || culprits.Any(c => !c.IsExternal || c.JarFileName == null)) return null;
        var jars = culprits.Select(c => c.JarFileName!).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var label = culprits.Count == 1
            ? $"Turn off {Shorten(names?.GetValueOrDefault(culprits[0].ModId) ?? Pretty(culprits[0].ModId), 22)} and relaunch"
            : $"Turn off {culprits.Count} mods and relaunch";
        return new CrashFix(CrashFixKind.DisableMods, label, jars);
    }

    private static string Name(DependencyProblem d) => d.ModName.Length > 0 ? d.ModName : Pretty(d.ModId);

    /// <summary>"some_cool-mod" -> "some cool mod" (ids are all a crash report gives us).</summary>
    public static string Pretty(string modId) => modId.Replace('_', ' ').Replace('-', ' ').Trim();

    private static string Gb(int mb) => $"{mb / 1024.0:0.#} GB";

    private static string Shorten(string s, int max) => s.Length <= max ? s : s[..(max - 3)] + "...";

    private static IReadOnlyList<string> Lines(params string?[] lines) =>
        lines.Where(l => !string.IsNullOrWhiteSpace(l)).Select(l => l!).ToList();
}
