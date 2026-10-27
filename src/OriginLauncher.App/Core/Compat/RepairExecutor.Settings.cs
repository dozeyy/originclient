using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core.Compat;

/// <summary>What Undo of a Java-arguments reset did.</summary>
public enum JavaArgsUndoOutcome
{
    /// <summary>The arguments from before the reset are back.</summary>
    Restored,

    /// <summary>No reset of that version is on record.</summary>
    NothingToUndo,

    /// <summary>The player has typed new arguments since: they are kept and nothing is put back.</summary>
    ChangedSince,
}

/// <summary>
/// The repairs that change a SETTING instead of a file (today: "Reset Java arguments"). Same rules as the file
/// repairs: reversible, never forced over something the player changed, and the only writer is
/// <c>SettingsStore.Update(mutate)</c> (passed in as <paramref name="update"/>, so the tests use a plain object).
/// The journal is the settings file itself: the reset and the copy kept for Undo are written in ONE Update, which
/// SettingsStore saves atomically (temp file + rename), so there is no moment where the old value is lost.
/// </summary>
public static partial class RepairExecutor
{
    /// <summary>Applies a plan's setting steps. File steps are refused here (they need <see cref="Apply"/>).</summary>
    public static RepairResult ApplySettings(RepairPlan plan, Action<Action<LauncherSettings>> update)
    {
        var applied = new List<AppliedStep>();
        foreach (var step in plan.Steps)
        {
            applied.Add(step switch
            {
                ResetJavaArgsStep reset => ResetJavaArgs(reset, update),
                _ => Fail(step, "That repair changes files, not a setting."),
            });
        }
        return new RepairResult(applied);
    }

    private static AppliedStep ResetJavaArgs(ResetJavaArgsStep step, Action<Action<LauncherSettings>> update)
    {
        string? error = null;
        try
        {
            update(s =>
            {
                var now = s.JvmArgsFor(step.Version);
                if (now == null)
                {
                    error = "This version already uses Origin's own Java arguments.";
                    return;
                }
                if (!Same(now, step.Old))
                {
                    error = "The Java arguments changed since the crash, so they were left as they are.";
                    return;
                }
                s.JvmArgsBeforeReset[step.Version] = Copy(now);
                s.JvmArgsByVersion.Remove(step.Version);
            });
        }
        catch (Exception ex) when (ex is System.IO.IOException or UnauthorizedAccessException)
        {
            return Fail(step, "Couldn't save the change - " + ex.Message);
        }
        return error == null ? new AppliedStep(step, true, null, step.Old.Arguments, "") : Fail(step, error);
    }

    /// <summary>True when Settings should offer Undo for <paramref name="version"/>: a reset is on record and the
    /// version has no arguments of its own since.</summary>
    public static bool CanUndoJavaArgsReset(LauncherSettings settings, string? version) =>
        version != null && settings.JvmArgsBeforeReset.ContainsKey(version) && settings.JvmArgsFor(version) == null;

    /// <summary>
    /// Puts back the arguments a reset took away, in one Update. Never over arguments the player typed since: then
    /// nothing changes and the record stays (<see cref="JavaArgsUndoOutcome.ChangedSince"/>).
    /// </summary>
    public static JavaArgsUndoOutcome UndoJavaArgsReset(string version, Action<Action<LauncherSettings>> update)
    {
        var outcome = JavaArgsUndoOutcome.NothingToUndo;
        update(s =>
        {
            if (!s.JvmArgsBeforeReset.TryGetValue(version, out var before) || before == null || before.IsEmpty)
            {
                s.JvmArgsBeforeReset.Remove(version);
                outcome = JavaArgsUndoOutcome.NothingToUndo;
                return;
            }
            if (s.JvmArgsFor(version) != null)
            {
                outcome = JavaArgsUndoOutcome.ChangedSince;
                return;
            }
            s.JvmArgsByVersion[version] = Copy(before);
            s.JvmArgsBeforeReset.Remove(version);
            outcome = JavaArgsUndoOutcome.Restored;
        });
        return outcome;
    }

    private static bool Same(VersionJvmOptions a, VersionJvmOptions b) =>
        string.Equals(a.Arguments, b.Arguments, StringComparison.Ordinal) && a.ReplacePresets == b.ReplacePresets;

    private static VersionJvmOptions Copy(VersionJvmOptions o) =>
        new() { Arguments = o.Arguments, ReplacePresets = o.ReplacePresets };
}
