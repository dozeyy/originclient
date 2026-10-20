using System.IO;

namespace OriginLauncher.App.Core.Launch;

/// <summary>Everything the crash window and the "Copy report" button need, found off the UI thread.</summary>
public sealed record CrashFindings(
    CrashAnalysis Analysis,
    CrashDiagnosis Diagnosis,
    string? LogText,
    string? CrashReportText,
    bool HasFreshCrashFile);

// The file-reading half of crash handling (the deciding half is pure: CrashClassifier).
// Reads the launch log's tail, the game's newest crash report and the JVM's hs_err dump,
// runs the mod-blame analyzer, then classifies. Call from a pool thread.
public static class CrashDiagnoser
{
    public static CrashFindings Run(string instanceDir, string? launchLogPath, int exitCode,
        int ramMb, int totalPhysicalMb, Func<CrashAction, bool>? actionTried = null)
    {
        var analysis = CrashAnalyzer.Analyze(instanceDir, launchLogPath);

        var logText = CrashAnalyzer.TryReadTail(launchLogPath, 512 * 1024);
        var crashFile = CrashAnalyzer.NewestCrashReport(instanceDir);
        var hsErrFile = CrashAnalyzer.NewestHsErr(instanceDir);
        var crashText = CrashAnalyzer.TryReadTail(crashFile, 256 * 1024);
        var hsErrText = CrashAnalyzer.TryReadTail(hsErrFile, 64 * 1024);

        // Last ~600 lines of the log: the crash is at the end, and an hours-long session's earlier
        // output must not be able to trigger a cause.
        var evidence = string.Join("\n", LastLines(logText, 600), crashText ?? "", hsErrText ?? "");
        var diagnosis = CrashClassifier.Classify(analysis, evidence, exitCode, ramMb, totalPhysicalMb, actionTried);

        return new CrashFindings(analysis, diagnosis, logText, crashText ?? hsErrText,
            HasFreshCrashFile: crashFile != null || hsErrFile != null);
    }

    private static string LastLines(string? text, int count)
    {
        if (string.IsNullOrEmpty(text)) return "";
        var lines = text.Split('\n');
        return lines.Length <= count ? text : string.Join("\n", lines.Skip(lines.Length - count));
    }
}
