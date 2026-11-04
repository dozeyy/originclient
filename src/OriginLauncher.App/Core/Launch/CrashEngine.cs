namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Crash Report V3's one entry point: evidence -> facts -> "was it a crash?" -> ordered rules -> one verdict.
/// Pure (no disk, no UI): <see cref="CrashDiagnoser"/> feeds it from the game folder, the corpus tests from fixture
/// folders (src/OriginLauncher.Tests/CrashCorpus).
/// </summary>
public static class CrashEngine
{
    public static CrashVerdict Diagnose(CrashEvidence evidence, bool fixTried = false) =>
        Diagnose(CrashFactsParser.Parse(evidence), fixTried);

    /// <param name="fixTried">The retry guard already spent this game's one automatic fix: no fix button now.</param>
    public static CrashVerdict Diagnose(CrashFacts facts, bool fixTried = false)
    {
        var found = CrashRules.Evaluate(facts);
        var pick = CrashRules.Pick(found);
        var (isCrash, reason) = Decide(facts, pick);

        if (fixTried && pick.Fix != null)
            pick = pick with
            {
                Fix = null,
                RecommendedFix = pick.RecommendedFix + " Origin already tried a fix once and the game stopped again, so it won't change anything else by itself.",
            };

        var others = found.Where(f => !ReferenceEquals(f, pick) && f.Rule != pick.Rule).ToList();
        return new CrashVerdict(isCrash, reason, pick, others)
        {
            // A one-launch try without the player's own mods, for when Origin can't name the cause well enough to
            // offer a fix. Never persistent: the Mods tab and the external-mods setting stay exactly as they were.
            OfferTryWithoutAddedMods = isCrash && !fixTried
                                       && (pick.Confidence < CrashConfidence.Strong || BlamesOnlyOrigin(pick))
                                       && facts.Mods.Any(m => m.IsExternal),
        };
    }

    // A mod problem pinned on Origin's own code (its jar, or a mod built into it such as 1.21.1's Sodium), or on a mod
    // nested in several of the player's jars (no one file to name), with no fix to offer: the player can't turn that
    // off, and the usual real cause is a mod they added, so the one-launch try without their mods is the way forward.
    private static bool BlamesOnlyOrigin(CrashFinding pick) =>
        pick.Cause == CrashCause.ModProblem && pick.Fix == null
        && pick.Culprits.Count > 0 && pick.Culprits.All(c => !c.IsExternal || c.JarFileName == null);

    /// <summary>
    /// Crash or normal close, decided BEFORE anything is shown (a normal close never opens a window):
    ///   - exit 0, or the player pressed Stop: a normal close;
    ///   - Minecraft logged its own "Stopping!" and left no crash file or confirmed cause: a normal quit, whatever the
    ///     exit code;
    ///   - while starting: a crash only with evidence (a crash file, an hs_err dump, a native exit code, a recognised
    ///     cause, or a thrown exception at the end of the log), so killing a booting game opens nothing;
    ///   - after the title screen: a crash only with a crash file, an hs_err dump, a native exit code or a cause at
    ///     strong or better (a bare exit code is Task Manager or a power button, not a crash to explain).
    /// </summary>
    public static (bool IsCrash, string Reason) Decide(CrashFacts f, CrashFinding pick)
    {
        if (f.StoppedByPlayer) return (false, "stopped by the player");
        if (f.ExitCode == 0) return (false, "exit code 0");
        var crashFile = f.HasCrashReport || f.HasHsErr;
        // A structured loader/JVM message (confirmed) can't come from a normal quit: it wins over a "Stopping!" line.
        if (f.CleanShutdown && !crashFile && pick.Confidence < CrashConfidence.Confirmed)
            return (false, "Minecraft logged a normal quit");
        if (crashFile) return (true, f.HasCrashReport ? "crash report written" : "Java crash dump written");
        if (f.NativeExitCode) return (true, "native exit code");
        if (f.CrashedWhileStarting)
        {
            if (pick.Confidence != CrashConfidence.Unknown) return (true, $"stopped while starting: {pick.Rule}");
            if (f.FatalSignal) return (true, "stopped while starting with an error in the log");
            return (false, "stopped while starting with no sign of a crash");
        }
        return pick.Confidence >= CrashConfidence.Strong
            ? (true, $"stopped in game: {pick.Rule}")
            : (false, "stopped in game with no sign of a crash");
    }
}

/// <summary>How far "raise memory" goes.</summary>
public static class MemoryBump
{
    /// <summary>
    /// The next memory setting after an out-of-memory crash: +2 GB on the 256 MB grid, never above
    /// RamAdvice.MaximumMb for this PC. Null when the setting is already at (or past) that cap.
    /// <paramref name="floorMb"/> (CrashMemory.ForLaunch) lifts the result to at least that, still within the cap:
    /// the raise is applied as Manual to every version, so on Auto it must not drop the ones that got more.
    /// </summary>
    public static int? Next(int currentMb, int totalPhysicalMb, int floorMb = 0)
    {
        var cap = RamAdvice.MaximumMb(totalPhysicalMb);
        if (currentMb >= cap) return null;
        var next = Math.Min(cap, Math.Max(floorMb, (currentMb + 2048) / RamAdvice.StepMb * RamAdvice.StepMb));
        return next > currentMb ? next : null;
    }
}

/// <summary>
/// At most ONE automatic relaunch per crash chain: after the crash window's fix (or its one-time try without added
/// mods) relaunches a game, a second crash of that same account AND version never offers another one, and the chain
/// resets once that game boots or after a while. Keyed by account and version: a fix tried on 1.21.1 says nothing
/// about 1.20.1 (before 2026-11-04 the key was the account alone, so one version's fix hid every other version's
/// fix button for 30 minutes and claimed "Origin already tried a fix once" about a version it never touched).
/// </summary>
public sealed class CrashRetryGuard
{
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(30);

    private readonly object _gate = new();
    private readonly Dictionary<string, (CrashFixKind Kind, DateTime At)> _spent = new();

    private static string Chain(string accountKey, string version) => accountKey + "|" + version;

    /// <summary>True when this account's game of this version has spent its one retry (still inside the window).</summary>
    public bool AnyTried(string accountKey, string version, DateTime nowUtc)
    {
        lock (_gate) return _spent.TryGetValue(Chain(accountKey, version), out var s) && nowUtc - s.At < Window;
    }

    /// <summary>Spends the retry. False when it was already spent (the caller must not relaunch).</summary>
    public bool TryBegin(string accountKey, string version, CrashFixKind kind, DateTime nowUtc)
    {
        var chain = Chain(accountKey, version);
        lock (_gate)
        {
            if (_spent.TryGetValue(chain, out var s) && nowUtc - s.At < Window) return false;
            _spent[chain] = (kind, nowUtc);
            return true;
        }
    }

    /// <summary>That game booted fine: its next crash starts a fresh chain.</summary>
    public void Clear(string accountKey, string version)
    {
        lock (_gate) _spent.Remove(Chain(accountKey, version));
    }
}
