using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Launch;

// Turns crash evidence (launch log tail + crash report + hs_err head, the mod
// blame CrashAnalyzer already made, the exit code) into ONE plain-language
// diagnosis with ONE main action. Pure: strings in, record out, so every cause
// is covered by tests with sample log snippets.
//
// Adding a cause = adding a row to Rows (pattern -> tag, headline, explanation,
// action, button label). Mod blame is not a row: it comes from CrashAnalyzer's
// structured parse and sits between the graphics rows and the native-crash row.
public static class CrashClassifier
{
    private sealed record Row(CrashCause Cause, Regex Pattern, string Tag, string Headline, string Explanation,
        CrashAction Action, string ActionLabel);

    private static Regex Rx(string pattern) =>
        new(pattern, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    // Strongest, most specific signals first. Generic words ("OpenGL", "token") are avoided on
    // purpose: every crash report's system details mention them, so they would match every crash.
    private static readonly Row[] EarlyRows =
    [
        new(CrashCause.OutOfMemory,
            Rx(@"java\.lang\.OutOfMemoryError|Could not reserve enough space|insufficient memory for the Java Runtime|GC overhead limit exceeded|Native memory allocation \(mmap\) failed|Out of Memory Error"),
            "OUT OF MEMORY", "Minecraft ran out of memory",
            "Minecraft needed more memory than it was given.",
            CrashAction.RaiseMemory, "Raise memory and relaunch"),

        new(CrashCause.JavaProblem,
            Rx(@"UnsupportedClassVersionError|compiled by a more recent version of the Java Runtime|Unable to locate a Java Runtime|Error occurred during initialization of VM|Could not create the Java Virtual Machine|Unrecognized VM option|Invalid maximum heap size|is not recognized as an internal or external command"),
            "JAVA", "Minecraft's Java wouldn't start",
            "Java refused to start the game. This is usually a launch setting Java doesn't accept (check Settings, Java arguments) or a Java install that is missing or broken.",
            CrashAction.OpenLogs, "Open logs folder"),

        new(CrashCause.DamagedFiles,
            Rx(@"Invalid or corrupt jarfile|Could not find or load main class|zip END header not found|java\.util\.zip\.Zip(Exception|Error)|Error opening zip file|Invalid CEN header|Unable to access jarfile"),
            "DAMAGED FILES", "Some game files look damaged",
            "A game file is missing or didn't finish downloading. Origin will check every file and fix what it finds on the next launch.",
            CrashAction.Relaunch, "Repair files and relaunch"),

        new(CrashCause.GraphicsDriver,
            Rx(@"GLFW error|WGL: |GLX: |Pixel format not accelerated|Couldn't set pixel format|Failed to create (the )?(window|OpenGL)|GL_OUT_OF_MEMORY|nvoglv64|atio6axx|atioglxx|aticfx64|amdxx64|nvwgf2um|ig[0-9a-z]{2,8}icd(32|64)|OpenGL 4\.\d required|OpenGL 3\.\d required"),
            "GRAPHICS DRIVER", "Your graphics driver rejected something",
            "The game couldn't draw with your graphics card. Updating the graphics driver (from NVIDIA, AMD or Intel, or Windows Update) fixes this most of the time. Trying again once can also clear a one-off hiccup.",
            CrashAction.Relaunch, "Try again"),
    ];

    private static readonly Row[] LateRows =
    [
        new(CrashCause.NativeCrash,
            Rx(@"A fatal error has been detected by the Java Runtime|EXCEPTION_ACCESS_VIOLATION|hs_err_pid\d+|SIGSEGV"),
            "WINDOWS CLOSED IT", "Windows shut Minecraft down",
            "Minecraft crashed below the level the launcher can see. That is usually a graphics driver, overlay software (screen recorders, RGB tools) or a bad mod. The logs folder has the details.",
            CrashAction.OpenLogs, "Open logs folder"),
    ];

    // Exit codes Windows gives a process that died in native code (access violation,
    // stack buffer overrun, DLL not found): no Java crash report exists for these.
    private static readonly HashSet<int> NativeExitCodes =
    [
        unchecked((int)0xC0000005), unchecked((int)0xC0000409), unchecked((int)0xC0000135), unchecked((int)0xC0000374),
    ];

    /// <param name="analysis">CrashAnalyzer's mod blame for the same evidence.</param>
    /// <param name="evidence">Log tail + crash report + hs_err head, as one string (may be empty).</param>
    /// <param name="ramMb">The memory the game was given.</param>
    /// <param name="totalPhysicalMb">The PC's memory (the cap for raising it).</param>
    /// <param name="actionTried">True for an action the retry guard already spent: it is not offered again.</param>
    public static CrashDiagnosis Classify(CrashAnalysis analysis, string evidence, int exitCode,
        int ramMb, int totalPhysicalMb, Func<CrashAction, bool>? actionTried = null)
    {
        evidence ??= "";
        foreach (var row in EarlyRows)
            if (row.Pattern.IsMatch(evidence))
                return Finish(FromRow(row, ramMb, totalPhysicalMb), actionTried);

        if (analysis.Culprits.Count > 0)
            return Finish(FromMods(analysis), actionTried);

        if (LateRows[0].Pattern.IsMatch(evidence) || NativeExitCodes.Contains(exitCode))
            return Finish(FromRow(LateRows[0], ramMb, totalPhysicalMb), actionTried);

        return Finish(new CrashDiagnosis(CrashCause.Unknown, "UNKNOWN", "Minecraft closed unexpectedly",
            "We couldn't pin down what went wrong. Mods you added are the most common cause, so trying again with only Origin's own mods will tell you if one of them is responsible.",
            CrashAction.OriginOnly, "Relaunch with Origin only"), actionTried);
    }

    private static CrashDiagnosis FromRow(Row row, int ramMb, int totalPhysicalMb)
    {
        var diagnosis = new CrashDiagnosis(row.Cause, row.Tag, row.Headline, row.Explanation, row.Action, row.ActionLabel);
        if (row.Action != CrashAction.RaiseMemory) return diagnosis;

        var next = MemoryBump.Next(ramMb, totalPhysicalMb);
        if (next == null)
            // Already at the most this PC should give Minecraft: raising it again would starve Windows.
            return diagnosis with
            {
                Explanation = "Minecraft used all the memory it was allowed, and that is already the most this PC can safely spare. Closing other programs, or removing some mods, will help.",
                Action = CrashAction.OpenLogs,
                ActionLabel = "Open logs folder",
            };
        return diagnosis with
        {
            Explanation = $"Minecraft needed more memory than the {ramMb / 1024.0:0.#} GB it was given. Origin can raise it to {next.Value / 1024.0:0.#} GB for you.",
            NewRamMb = next,
        };
    }

    private static CrashDiagnosis FromMods(CrashAnalysis analysis)
    {
        var external = analysis.Culprits.Where(c => c.IsExternal).ToList();
        if (external.Count == 0)
        {
            // Only Origin's own parts were named: the player's files are not the cause.
            var built = analysis.Culprits[0];
            return new CrashDiagnosis(CrashCause.ModProblem, "ORIGIN MOD", "One of Origin's built-in mods failed",
                $"{Pretty(built.ModId)} is part of Origin, not something you added. Updating the launcher usually fixes this.",
                CrashAction.OpenLogs, "Open logs folder", built.ModId, built.JarFileName);
        }
        if (external.Count == 1 && external[0].JarFileName != null)
        {
            var one = external[0];
            var name = Pretty(one.ModId);
            return new CrashDiagnosis(CrashCause.ModProblem, "MOD PROBLEM", $"A mod didn't load: {name}",
                $"{analysis.Reason} It is not part of Origin, so it can be switched off without touching the rest. You can turn it back on in the Mods tab.",
                CrashAction.DisableMod, $"Relaunch without {Shorten(name, 22)}", one.ModId, one.JarFileName);
        }
        var names = string.Join(", ", external.Select(c => Pretty(c.ModId)).Take(3));
        return new CrashDiagnosis(CrashCause.ModProblem, "MOD PROBLEM", "Some of your added mods didn't load",
            $"{analysis.Reason} Named: {names}. Relaunching with only Origin's own mods will confirm it.",
            CrashAction.OriginOnly, "Relaunch with Origin only");
    }

    // The retry guard spent this action already: the main button becomes "open logs" so a
    // failing fix can never loop. The headline and cause stay.
    private static CrashDiagnosis Finish(CrashDiagnosis d, Func<CrashAction, bool>? actionTried)
    {
        if (actionTried == null || d.Action is CrashAction.None or CrashAction.OpenLogs || !actionTried(d.Action))
            return d;
        return d with
        {
            Explanation = d.Explanation + " Origin already tried that once and it didn't help, so the logs are the next step.",
            Action = CrashAction.OpenLogs,
            ActionLabel = "Open logs folder",
            NewRamMb = null,
        };
    }

    /// <summary>"some_cool-mod" -> "some cool mod" (ids are all a crash report gives us).</summary>
    public static string Pretty(string modId) =>
        modId.Replace('_', ' ').Replace('-', ' ').Trim();

    private static string Shorten(string s, int max) => s.Length <= max ? s : s[..(max - 3)] + "...";
}

/// <summary>How far "raise memory" goes.</summary>
public static class MemoryBump
{
    /// <summary>
    /// The next memory setting after an out-of-memory crash: +2 GB on the 256 MB grid, never above
    /// RamAdvice.MaximumMb for this PC. Null when the setting is already at (or past) that cap.
    /// </summary>
    public static int? Next(int currentMb, int totalPhysicalMb)
    {
        var cap = RamAdvice.MaximumMb(totalPhysicalMb);
        if (currentMb >= cap) return null;
        var next = Math.Min(cap, (currentMb + 2048) / RamAdvice.StepMb * RamAdvice.StepMb);
        return next > currentMb ? next : null;
    }
}

/// <summary>When the crash window appears at all.</summary>
public static class CrashGate
{
    /// <summary>
    /// A normal quit (exit 0) or one the player asked for never shows it. A crash while the game was
    /// still starting always does. A non-zero exit later (the player was already in the game) only does
    /// when there is real evidence it crashed: a fresh crash report, or a cause we recognise. A bare
    /// exit code could be Task Manager or a power button, which is not a crash to explain.
    /// </summary>
    public static bool ShouldShow(int exitCode, bool stoppedByPlayer, bool crashedWhileStarting,
        bool hasFreshCrashFile, CrashDiagnosis diagnosis)
    {
        if (exitCode == 0 || stoppedByPlayer) return false;
        if (crashedWhileStarting) return true;
        return hasFreshCrashFile || diagnosis.Cause != CrashCause.Unknown;
    }
}

/// <summary>
/// At most ONE relaunch per crash chain: after the crash window's action relaunches a game, a second
/// crash of that same account never offers another automatic fix, and the chain resets once a game
/// boots or after a while. Keyed by the account's game key.
/// </summary>
public sealed class CrashRetryGuard
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(30);

    private readonly object _gate = new();
    private readonly Dictionary<string, (CrashAction Action, DateTime At)> _spent = new();

    /// <summary>True when this key has spent its one retry (and it is still inside the window).</summary>
    public bool AnyTried(string key, DateTime nowUtc)
    {
        lock (_gate) return _spent.TryGetValue(key, out var s) && nowUtc - s.At < Window;
    }

    /// <summary>Spends the retry. False when it was already spent (the caller must not relaunch).</summary>
    public bool TryBegin(string key, CrashAction action, DateTime nowUtc)
    {
        lock (_gate)
        {
            if (_spent.TryGetValue(key, out var s) && nowUtc - s.At < Window) return false;
            _spent[key] = (action, nowUtc);
            return true;
        }
    }

    /// <summary>The game booted fine: the next crash starts a fresh chain.</summary>
    public void Clear(string key)
    {
        lock (_gate) _spent.Remove(key);
    }
}
