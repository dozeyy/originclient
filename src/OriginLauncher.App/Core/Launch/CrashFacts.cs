using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Launch;

/// <summary>One jar in the launch set the crashed game loaded. ModId is "" when the jar could not be read (a
/// damaged jar still has to be matchable by its file name). Packages: its code packages, only filled for added mods
/// (Origin's own jars run on every launch, so blaming them by stack is noise).</summary>
public sealed record CrashModEntry(string ModId, string? JarFileName, bool IsExternal, IReadOnlyCollection<string>? Packages = null);

/// <summary>
/// Everything known about a game that stopped, as raw text and numbers: the launch log (the game's stdout/stderr the
/// launcher captured), the game's own logs/latest.log, the newest crash-reports/crash-*.txt, the JVM's hs_err dump,
/// the exit code and the launch mod set. Produced by <see cref="CrashDiagnoser"/> from disk, or by the test corpus
/// from a folder; <see cref="CrashFactsParser.Parse"/> turns it into <see cref="CrashFacts"/>.
/// </summary>
public sealed record CrashEvidence(
    int ExitCode,
    bool StoppedByPlayer,
    bool CrashedWhileStarting,
    string? LaunchLog,
    string? LatestLog,
    string? CrashReport,
    string? HsErr,
    IReadOnlyList<CrashModEntry> Mods,
    int RamMb,
    int TotalPhysicalMb);

public enum DependencyKind { Missing, WrongVersion, Incompatible, Unloadable }

/// <summary>One line of Fabric's "Incompatible mods found!" block: the mod (subject) and what it needs.</summary>
public sealed record DependencyProblem(string ModId, string ModName, DependencyKind Kind, string Target, string Needs, string Line);

/// <summary>A mixin apply/injection failure, with the config and the owning mod when the line names them.</summary>
public sealed record MixinFailure(string? ModId, string? Config, string Line);

/// <summary>One block of the last exception chain: its header line and the classes of its frames.</summary>
public sealed record ChainBlock(string Header, IReadOnlyList<string> Classes);

/// <summary>Two or more jars claiming one mod id (old Fabric wording, Forge's, or a clash with a built-in id).</summary>
public sealed record DuplicateMod(string ModId, IReadOnlyList<string> Jars, string Line);

/// <summary>
/// The parsed facts of one crash (pure data). Each fact keeps the line it came from so the window's technical details
/// can quote it. Built once, read by every rule in <see cref="CrashRules"/>.
/// </summary>
public sealed record CrashFacts
{
    public int ExitCode { get; init; }
    public bool StoppedByPlayer { get; init; }
    public bool CrashedWhileStarting { get; init; }
    public bool HasCrashReport { get; init; }
    public bool HasHsErr { get; init; }
    /// <summary>Windows ended it in native code (access violation, stack overrun, missing DLL, heap corruption).</summary>
    public bool NativeExitCode { get; init; }
    /// <summary>Minecraft logged its own "Stopping!" (a normal quit) near the end of its log.</summary>
    public bool CleanShutdown { get; init; }
    /// <summary>The end of the logs shows a thrown exception or a loader/JVM fatal line (not just INFO lines).</summary>
    public bool FatalSignal { get; init; }

    public IReadOnlyList<DependencyProblem> Dependencies { get; init; } = [];
    public IReadOnlyList<DuplicateMod> Duplicates { get; init; } = [];
    public IReadOnlyList<(string ModId, string Jar, string Line)> ForgeErrored { get; init; } = [];
    public IReadOnlyList<(string ModId, string Line)> ForgeMissing { get; init; } = [];
    public IReadOnlyList<MixinFailure> MixinFailures { get; init; } = [];
    public IReadOnlyList<string> SuspectedMods { get; init; } = [];
    public string? SuspectedLine { get; init; }
    /// <summary>The last exception chain, deepest "Caused by" first.</summary>
    public IReadOnlyList<ChainBlock> CauseChain { get; init; } = [];

    public string? HeapOomLine { get; init; }
    public string? NativeOomLine { get; init; }
    public string? JvmStartLine { get; init; }
    public string? UnrecognizedOption { get; init; }
    public bool HeapReserveFailed { get; init; }
    public string? ClassVersionLine { get; init; }
    public string? ClassVersionClass { get; init; }
    public string? DamagedLine { get; init; }
    /// <summary>The damaged jar's file name when the line names one (never a path).</summary>
    public string? DamagedJar { get; init; }
    /// <summary>The damaged file is part of the game install (libraries, versions, the main class), not a mod.</summary>
    public bool DamagedGameFiles { get; init; }
    public string? GraphicsLine { get; init; }
    /// <summary>hs_err "Problematic frame" native module, e.g. "nvoglv64.dll".</summary>
    public string? ProblematicModule { get; init; }
    public string? ProblematicFrameLine { get; init; }
    public string? JavaVersion { get; init; }
    public int? JavaMajor { get; init; }
    /// <summary>The crash report's "Description:" line.</summary>
    public string? CrashDescription { get; init; }

    public IReadOnlyList<CrashModEntry> Mods { get; init; } = [];
    public int RamMb { get; init; }
    public int TotalPhysicalMb { get; init; }
}

/// <summary>
/// Evidence -> facts. Pure: strings in, record out, no disk. Reads only the END of each log (the last 600 lines): the
/// crash is at the end, and an hours-long session's earlier output must not be able to trigger a cause.
/// </summary>
public static class CrashFactsParser
{
    public const int LogTailLines = 600;
    private const int FatalWindowLines = 120;

    private static Regex Rx(string pattern, RegexOptions extra = RegexOptions.None) =>
        new(pattern, RegexOptions.CultureInvariant | RegexOptions.Compiled | extra);

    // Exit codes Windows gives a process that died in native code: no Java crash report exists for these.
    private static readonly HashSet<int> NativeCodes =
    [
        unchecked((int)0xC0000005), unchecked((int)0xC0000409), unchecked((int)0xC0000135), unchecked((int)0xC0000374),
    ];

    private static readonly Regex Stopping = Rx(@"\bStopping!");
    private static readonly Regex Resolution = Rx(@"Incompatible mods? (?:found|set)|Some of your mods are incompatible|Mod resolution (?:encountered|failed)", RegexOptions.IgnoreCase);
    // "- Mod 'Some Mod' (somemod) 1.2.3 requires version 1.21 of minecraft, but only the wrong version is present: 1.21.1!"
    // (Fabric Loader 0.15+ Messages.properties resolution.depends.* / resolution.breaks.invalid).
    private static readonly Regex DepLine = Rx(@"[Mm]od '(?<name>[^'\r\n]*)' \((?<id>[a-z0-9_\-]+)\) (?<ver>\S+) (?<verb>requires|is incompatible with) (?<rest>[^\r\n]+)");
    private static readonly Regex OldDuplicate = Rx(@"[Dd]uplicate[^\r\n]*?\b(?:mod id|mods?)\b[^\r\n]*?['""`](?<id>[a-z0-9_\-]+)['""`](?<rest>[^\r\n]*)");
    private static readonly Regex BuiltinClash = Rx(@"Mods share ID with builtin mod (?<id>[A-Za-z0-9_\-\.]+): (?<rest>[^\r\n]*)");
    private static readonly Regex ForgeDuplicate = Rx(@"Found a duplicate mod (?<id>[A-Za-z0-9_\-\.]+) at (?<rest>[^\r\n]*)");
    private static readonly Regex ForgeMissingRx = Rx(@"The mod (?<id>[A-Za-z0-9_\-\.]+) \([^)\r\n]*\) requires mods? [^\r\n]*");
    private static readonly Regex ForgeErroredRx = Rx(@"^\s*[ULCHIJAD]*E[ULCHIJADE]*\s+(?<id>[A-Za-z0-9_\-\.]+)\{[^}\r\n]*\}\s+\[[^\]\r\n]*\]\s+\((?<jar>[^)\r\n]+)\)", RegexOptions.Multiline);
    private static readonly Regex MixinApply = Rx(@"Mixin apply(?:ing)?(?: for mod (?<mod>[a-z0-9_\-]+))? failed(?:\s+(?<cfg>[A-Za-z0-9_\-\.]+\.json))?[^\r\n]*", RegexOptions.IgnoreCase);
    private static readonly Regex MixinFromMod = Rx(@"(?<cfg>[A-Za-z0-9_\-\.]+\.json):[\w$.]+ from mod (?<mod>[a-z0-9_\-]+)");
    private static readonly Regex InjectionFailure = Rx(@"Critical injection failure[^\r\n]*|InvalidInjectionException[^\r\n]*|InvalidMixinException[^\r\n]*|MixinApplyError[^\r\n]*|MixinTransformerError[^\r\n]*");
    private static readonly Regex Suspected = Rx(@"Suspected Mods?:?(?<rest>[^\r\n]*)");
    private static readonly Regex ParenId = Rx(@"\((?<id>[a-z0-9_\-]+)\)");

    private static readonly Regex HeapOom = Rx(@"java\.lang\.OutOfMemoryError[^\r\n]*|GC overhead limit exceeded");
    private static readonly Regex NativeOom = Rx(@"Native memory allocation \((?:mmap|malloc)\) failed[^\r\n]*|insufficient memory for the Java Runtime[^\r\n]*");
    private static readonly Regex HeapReserve = Rx(@"Could not reserve enough space for[^\r\n]*heap[^\r\n]*|Invalid (?:maximum|initial) heap size[^\r\n]*|Initial heap size set to a larger value than the maximum heap size");
    private static readonly Regex BadOption = Rx(@"Unrecognized VM option '(?<opt>[^'\r\n]+)'|Unrecognized option: (?<opt>\S+)");
    private static readonly Regex JvmStart = Rx(@"Could not create the Java Virtual Machine|Error occurred during initialization of VM|Unable to locate a Java Runtime|is not recognized as an internal or external command");
    private static readonly Regex ClassVersion = Rx(@"UnsupportedClassVersionError: (?<cls>[\w/$.]+) has been compiled by a more recent version of the Java Runtime[^\r\n]*|compiled by a more recent version of the Java Runtime[^\r\n]*");
    private static readonly Regex Damaged = Rx(@"Invalid or corrupt jarfile[^\r\n]*|Error analyzing [^\r\n]*|zip END header not found|java\.util\.zip\.Zip(?:Exception|Error)[^\r\n]*|Error opening zip file[^\r\n]*|Invalid CEN header[^\r\n]*|Unable to access jarfile[^\r\n]*|Could not find or load main class[^\r\n]*|is not a ZIP archive|Archive is not a ZIP archive[^\r\n]*|ZipException[^\r\n]*");
    private static readonly Regex JarName = Rx(@"(?<path>[^\s'""\[\]<>|:*?,]*[\\/])?(?<name>[^\\/\s'""\[\]<>|:*?,]+\.jar)\b", RegexOptions.IgnoreCase);
    private static readonly Regex Graphics = Rx(@"GLFW error[^\r\n]*|WGL: [^\r\n]*|GLX: [^\r\n]*|Pixel format not accelerated|Couldn't set pixel format|Failed to create (?:the )?(?:window|OpenGL)[^\r\n]*|GL_OUT_OF_MEMORY|OpenGL [34]\.\d required[^\r\n]*", RegexOptions.IgnoreCase);
    private static readonly Regex Frame = Rx(@"# Problematic frame:\s*\r?\n#\s*(?<line>\w+\s+\[(?<module>[^+\]\r\n]+)[^\r\n]*)");
    private static readonly Regex JavaVer = Rx(@"Java Version:\s*(?<v>[^\r\n]+)|# JRE version:[^(\r\n]*\((?<v>[^)\r\n]+)\)|# JRE version:\s*(?<v>[^\r\n]+)");
    private static readonly Regex Description = Rx(@"^Description:\s*(?<d>[^\r\n]+)", RegexOptions.Multiline);
    // A thrown exception or a loader/JVM fatal line near the end of the log.
    private static readonly Regex Fatal = Rx(@"Exception in thread|[\w$.]+(?:Exception|Error)(?::|\s*$)|^\s*Caused by:|A mod crashed on startup|Incompatible mods found|Failed to start Minecraft|Minecraft has crashed|---- Minecraft Crash Report|#@!@# Game crashed|Could not create the Java Virtual Machine|Mod discovery failed", RegexOptions.Multiline);

    // Frames that are never a mod's own code.
    internal static readonly string[] NotModCode =
    [
        "java.", "javax.", "jdk.", "sun.", "net.fabricmc.", "org.spongepowered.", "com.llamalad7.", "net.minecraft.",
        "com.mojang.", "org.lwjgl.", "io.netty.", "com.google.", "org.apache.", "org.slf4j.", "cpw.mods.", "net.minecraftforge.",
    ];

    private static readonly Regex FrameLine = Rx(@"^\s*at (?:knot//)?(?:[\w.]+/)?(?<q>[A-Za-z_$][\w$]*(?:\.[\w$<>]+)+)\(", RegexOptions.Multiline);

    public static CrashFacts Parse(CrashEvidence e)
    {
        var logs = string.Join("\n", LastLines(e.LaunchLog, LogTailLines), LastLines(e.LatestLog, LogTailLines));
        var crash = e.CrashReport ?? "";
        var hsErr = e.HsErr ?? "";
        var all = string.Join("\n", logs, crash, hsErr);
        var logsAndCrash = logs + "\n" + crash;

        var damagedMatch = Damaged.Match(all);
        string? damagedJar = null;
        var damagedGame = false;
        if (damagedMatch.Success)
        {
            var line = LineAt(all, damagedMatch.Index);
            var jar = JarName.Match(line);
            if (jar.Success)
            {
                damagedJar = jar.Groups["name"].Value;
                var dir = jar.Groups["path"].Value.Replace('\\', '/');
                damagedGame = dir.Contains("/libraries/", StringComparison.OrdinalIgnoreCase)
                              || dir.Contains("/versions/", StringComparison.OrdinalIgnoreCase);
            }
            damagedGame |= line.Contains("Could not find or load main class", StringComparison.Ordinal);
        }

        var frame = Frame.Match(hsErr);
        var javaVer = JavaVer.Match(crash + "\n" + hsErr + "\n" + logs);
        var classVersion = ClassVersion.Match(all);
        var badOption = BadOption.Match(all);
        var jvmStart = JvmStart.Match(all);
        var heapReserve = HeapReserve.Match(all);

        return new CrashFacts
        {
            ExitCode = e.ExitCode,
            StoppedByPlayer = e.StoppedByPlayer,
            CrashedWhileStarting = e.CrashedWhileStarting,
            HasCrashReport = !string.IsNullOrWhiteSpace(e.CrashReport),
            HasHsErr = !string.IsNullOrWhiteSpace(e.HsErr),
            NativeExitCode = NativeCodes.Contains(e.ExitCode),
            CleanShutdown = Stopping.IsMatch(LastLines(e.LatestLog ?? e.LaunchLog, 40)),
            FatalSignal = Fatal.IsMatch(LastLines(e.LaunchLog, FatalWindowLines))
                          || Fatal.IsMatch(LastLines(e.LatestLog, FatalWindowLines)),

            Dependencies = Resolution.IsMatch(logsAndCrash) ? ParseDependencies(logsAndCrash) : [],
            Duplicates = ParseDuplicates(logsAndCrash),
            ForgeErrored = ForgeErroredRx.Matches(crash)
                .Where(m => !ForgeLoaderIds.Contains(m.Groups["id"].Value))
                .Select(m => (m.Groups["id"].Value, m.Groups["jar"].Value.Trim(), m.Value.Trim()))
                .DistinctBy(t => t.Item1, StringComparer.OrdinalIgnoreCase).ToList(),
            ForgeMissing = ForgeMissingRx.Matches(logsAndCrash)
                .Select(m => (m.Groups["id"].Value, m.Value.Trim()))
                .DistinctBy(t => t.Item1, StringComparer.OrdinalIgnoreCase).ToList(),
            MixinFailures = ParseMixin(logsAndCrash, e.Mods),
            SuspectedMods = ParseSuspected(crash, out var suspectedLine),
            SuspectedLine = suspectedLine,
            CauseChain = LastChainBlocks(logsAndCrash),

            HeapOomLine = Line(HeapOom.Match(all), all),
            NativeOomLine = Line(NativeOom.Match(all), all),
            JvmStartLine = badOption.Success ? Line(badOption, all) : Line(jvmStart, all),
            UnrecognizedOption = badOption.Success ? badOption.Groups["opt"].Value : null,
            HeapReserveFailed = heapReserve.Success,
            ClassVersionLine = Line(classVersion, all),
            ClassVersionClass = classVersion.Success && classVersion.Groups["cls"].Success
                ? classVersion.Groups["cls"].Value.Replace('/', '.') : null,
            DamagedLine = Line(damagedMatch, all),
            DamagedJar = damagedJar,
            DamagedGameFiles = damagedGame,
            GraphicsLine = Line(Graphics.Match(all), all),
            ProblematicModule = frame.Success ? frame.Groups["module"].Value.Trim() : null,
            ProblematicFrameLine = frame.Success ? frame.Groups["line"].Value.Trim() : null,
            JavaVersion = javaVer.Success ? javaVer.Groups["v"].Value.Trim() : null,
            JavaMajor = javaVer.Success ? JavaMajorOf(javaVer.Groups["v"].Value) : null,
            CrashDescription = Description.Match(crash) is { Success: true } d ? d.Groups["d"].Value.Trim() : null,

            Mods = e.Mods,
            RamMb = e.RamMb,
            TotalPhysicalMb = e.TotalPhysicalMb,
        };
    }

    private static readonly HashSet<string> ForgeLoaderIds = new(StringComparer.OrdinalIgnoreCase) { "mcp", "FML", "Forge" };
    private static readonly HashSet<string> LoaderIds = new(StringComparer.OrdinalIgnoreCase) { "minecraft", "java", "fabricloader", "mixinextras" };

    private static List<DependencyProblem> ParseDependencies(string text)
    {
        var list = new List<DependencyProblem>();
        foreach (Match m in DepLine.Matches(text))
        {
            var id = m.Groups["id"].Value;
            if (LoaderIds.Contains(id)) continue;
            var rest = m.Groups["rest"].Value;
            var incompatible = m.Groups["verb"].Value.StartsWith("is incompatible", StringComparison.Ordinal);
            var kind = incompatible ? DependencyKind.Incompatible
                : rest.Contains("which is missing", StringComparison.Ordinal) ? DependencyKind.Missing
                : rest.Contains("but only the wrong version", StringComparison.Ordinal) ? DependencyKind.WrongVersion
                : DependencyKind.Unloadable;
            // "{needs} of {target}, which ..." -> needs = "version 1.20.1", target = "minecraft".
            var cut = rest.IndexOfAny([',', '!']);
            var head = cut >= 0 ? rest[..cut] : rest;
            var of = head.LastIndexOf(" of ", StringComparison.Ordinal);
            var needs = of >= 0 ? head[..of].Trim() : "";
            var targetText = of >= 0 ? head[(of + 4)..].Trim() : head.Trim();
            var paren = ParenId.Matches(targetText);
            var target = paren.Count > 0 ? paren[^1].Groups["id"].Value
                : targetText.Replace("mod ", "").Trim('\'', ' ');
            list.Add(new DependencyProblem(id, m.Groups["name"].Value, kind, target, needs, m.Value.Trim()));
        }
        return list.DistinctBy(d => (d.ModId, d.Kind, d.Target)).ToList();
    }

    private static List<DuplicateMod> ParseDuplicates(string text)
    {
        var list = new List<DuplicateMod>();
        foreach (var rx in new[] { BuiltinClash, ForgeDuplicate, OldDuplicate })
            foreach (Match m in rx.Matches(text))
            {
                var jars = JarName.Matches(m.Groups["rest"].Value).Select(j => j.Groups["name"].Value)
                    .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
                list.Add(new DuplicateMod(m.Groups["id"].Value, jars, m.Value.Trim()));
            }
        return list.DistinctBy(d => d.ModId, StringComparer.OrdinalIgnoreCase).ToList();
    }

    private static List<MixinFailure> ParseMixin(string text, IReadOnlyList<CrashModEntry> mods)
    {
        var list = new List<MixinFailure>();
        foreach (Match m in MixinApply.Matches(text))
        {
            var from = MixinFromMod.Match(m.Value);
            var mod = m.Groups["mod"].Success ? m.Groups["mod"].Value : from.Success ? from.Groups["mod"].Value : null;
            var cfg = m.Groups["cfg"].Success ? m.Groups["cfg"].Value : from.Success ? from.Groups["cfg"].Value : null;
            list.Add(new MixinFailure(mod ?? ModOfConfig(cfg, mods), cfg, m.Value.Trim()));
        }
        // Mixin's own "Critical injection failure ... [... -> somemod.mixins.json:Foo from mod somemod -> ...]".
        foreach (Match m in InjectionFailure.Matches(text))
            if (MixinFromMod.Match(m.Value) is { Success: true } from)
                list.Add(new MixinFailure(from.Groups["mod"].Value, from.Groups["cfg"].Value, m.Value.Trim()));
        return list.Where(f => f.ModId == null || !LoaderIds.Contains(f.ModId))
            .DistinctBy(f => (f.ModId ?? "", f.Config ?? "")).ToList();
    }

    // "somemod.mixins.json" / "somemod-client.mixins.json" -> "somemod", only when that id is in the launch set.
    // Exact id first, then the LONGEST id the stem starts with: "carpet-extra.mixins.json" is carpet-extra's, not
    // carpet's, and "sodium-extra.mixins.json" must not blame Origin's own sodium.
    internal static string? ModOfConfig(string? cfg, IReadOnlyList<CrashModEntry> mods)
    {
        if (cfg == null) return null;
        var stem = cfg.Split('.')[0];
        var exact = mods.FirstOrDefault(m => m.ModId.Length > 0 && stem.Equals(m.ModId, StringComparison.OrdinalIgnoreCase));
        if (exact != null) return exact.ModId;
        return mods.Where(m => m.ModId.Length > 0
                && (stem.StartsWith(m.ModId + "-", StringComparison.OrdinalIgnoreCase)
                    || stem.StartsWith(m.ModId + "_", StringComparison.OrdinalIgnoreCase)))
            .OrderByDescending(m => m.ModId.Length).FirstOrDefault()?.ModId;
    }

    private static List<string> ParseSuspected(string crash, out string? line)
    {
        line = null;
        var ids = new List<string>();
        foreach (Match m in Suspected.Matches(crash))
        {
            line ??= m.Value.Trim();
            ids.AddRange(ParenId.Matches(m.Groups["rest"].Value).Select(p => p.Groups["id"].Value));
            // Minecraft 1.20+ lists them on the next lines, one tab in: "\tSome Mod (somemod), Version: 1.0.0", with
            // deeper-indented detail lines (issue tracker, frames) under each, which are skipped.
            var after = crash.IndexOf('\n', m.Index);
            if (after < 0) continue;
            foreach (var raw in crash[(after + 1)..].Split('\n'))
            {
                var l = raw.TrimEnd('\r');
                if (l.Length == 0 || !char.IsWhiteSpace(l[0])) break;
                if (l.StartsWith("\t\t", StringComparison.Ordinal) || l.StartsWith("    ", StringComparison.Ordinal)) continue;
                if (ParenId.Match(l) is { Success: true } p)
                {
                    ids.Add(p.Groups["id"].Value);
                    if (line.EndsWith(':')) line += " " + l.Trim();
                }
            }
        }
        return ids.Where(id => !LoaderIds.Contains(id)).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    // The last exception chain in the text (a throwable and its "Caused by" blocks), deepest block first. Only the
    // final chain counts: an earlier, harmless logged exception must not decide the blame.
    internal static List<ChainBlock> LastChainBlocks(string text)
    {
        // A lone exception with no "Caused by" (the usual crash-report shape) is a chain of one block.
        var lines = text.Split('\n');
        var last = -1;
        for (var i = lines.Length - 1; i >= 0 && last < 0; i--)
            if (lines[i].TrimStart().StartsWith("Caused by:", StringComparison.Ordinal)) last = i;
        for (var i = lines.Length - 1; i >= 0 && last < 0; i--)
            if (lines[i].TrimStart().StartsWith("at ", StringComparison.Ordinal)) last = i;
        if (last < 0) return [];

        static bool IsChainLine(string l)
        {
            var t = l.TrimStart();
            return t.StartsWith("at ", StringComparison.Ordinal) || t.StartsWith("...", StringComparison.Ordinal)
                || t.StartsWith("Caused by:", StringComparison.Ordinal) || t.StartsWith("Suppressed:", StringComparison.Ordinal);
        }

        var start = last;
        while (start > 0 && IsChainLine(lines[start - 1])) start--;
        if (start > 0) start--; // the top exception's own header line

        var blocks = new List<(string Header, List<string> Classes)>();
        for (var i = start; i < lines.Length; i++)
        {
            var line = lines[i].TrimEnd('\r');
            if (i > start && !IsChainLine(line)) break;
            var isHeader = i == start || line.TrimStart().StartsWith("Caused by:", StringComparison.Ordinal);
            if (isHeader) blocks.Add((line.Trim(), []));
            else if (FrameLine.Match(line) is { Success: true } m)
            {
                var qualified = m.Groups["q"].Value;
                var dot = qualified.LastIndexOf('.');
                if (dot > 0) blocks[^1].Classes.Add(qualified[..dot]);
            }
        }
        blocks.Reverse();
        return blocks.Select(b => new ChainBlock(b.Header, b.Classes)).ToList();
    }

    /// <summary>"21.0.2+13-LTS" -> 21, "1.8.0_402" -> 8.</summary>
    public static int? JavaMajorOf(string version)
    {
        var m = Regex.Match(version, @"(\d+)(?:\.(\d+))?");
        if (!m.Success || !int.TryParse(m.Groups[1].Value, out var major)) return null;
        if (major == 1 && int.TryParse(m.Groups[2].Value, out var minor)) return minor;
        return major;
    }

    internal static string LastLines(string? text, int count)
    {
        if (string.IsNullOrEmpty(text)) return "";
        var lines = text.Split('\n');
        return lines.Length <= count ? text : string.Join("\n", lines.Skip(lines.Length - count));
    }

    private static string? Line(Match m, string text) => m.Success ? Clip(LineAt(text, m.Index)) : null;

    private static string LineAt(string text, int index)
    {
        var start = text.LastIndexOf('\n', Math.Max(0, index - 1)) + 1;
        if (index == 0) start = 0;
        var end = text.IndexOf('\n', index);
        return text[start..(end < 0 ? text.Length : end)].Trim();
    }

    private static string Clip(string line) => line.Length <= 300 ? line : line[..300] + " ...";
}
