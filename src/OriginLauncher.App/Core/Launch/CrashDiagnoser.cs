namespace OriginLauncher.App.Core.Launch;

/// <summary>Everything the crash window and the "Copy report" button need, found off the UI thread.</summary>
/// <param name="LogText">The launch log tail (stdout/stderr the launcher captured).</param>
/// <param name="CrashReportText">The crash report, or the hs_err dump when there is no report.</param>
public sealed record CrashFindings(
    CrashVerdict Verdict,
    CrashEvidence Evidence,
    string? LogText,
    string? CrashReportText);

// Reads what the stopped game left (CrashAnalyzer.ReadEvidence) and diagnoses it (CrashEngine). Call from a pool
// thread. Pure reading: nothing is written, uploaded or deleted.
public static class CrashDiagnoser
{
    public static CrashFindings Run(string instanceDir, string? launchLogPath, int exitCode, bool stoppedByPlayer,
        bool crashedWhileStarting, int ramMb, int totalPhysicalMb, bool fixTried, DateTime? startedUtc = null)
    {
        var evidence = CrashAnalyzer.ReadEvidence(instanceDir, launchLogPath, exitCode, stoppedByPlayer,
            crashedWhileStarting, ramMb, totalPhysicalMb, startedUtc);
        var verdict = CrashEngine.Diagnose(evidence, fixTried);
        return new CrashFindings(verdict, evidence, evidence.LaunchLog ?? evidence.LatestLog,
            evidence.CrashReport ?? evidence.HsErr);
    }
}
