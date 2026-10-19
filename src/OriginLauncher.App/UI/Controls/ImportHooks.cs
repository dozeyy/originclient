using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;

namespace OriginLauncher.App.UI.Controls;

// The three places the Import drawer reaches into the import wiring. The wiring agent's versions of these
// (branch claude/import-wiring) are not on this branch's base, so each is written against the signature the
// wiring review describes, in ONE place: when that branch is merged, only the marked line in each method changes.
/// <summary>
/// What the window's close button needs from the import drawer: is an import writing, and may it be cancelled.
/// MERGE: the same name and shape as IImportCloseHost on claude/import-wiring; when that branch merges, delete this
/// declaration and keep the `: IImportCloseHost` on ImportPanel.
/// </summary>
public interface IImportCloseHost
{
    bool IsApplying { get; }

    /// <summary>Asks "Cancel the import?". True = cancel (the engine rolls back), false = keep importing.</summary>
    Task<bool> ConfirmCancelAsync();
}

internal static class ImportHooks
{
    // Each placeholder is ONE line. MERGE: when claude/import-wiring lands, replace that line with the named API.

    /// <summary>Never read IImportBackend.SupportedTargets on the UI thread: this runs the read off it.</summary>
    public static Task<IReadOnlyList<string>> TargetsAsync(IImportBackend backend, CancellationToken ct) =>
        Task.Run(() => backend.SupportedTargets, ct); // MERGE: backend.GetSupportedTargetsAsync(ct)

    /// <summary>"Browse for a folder": the caller passes its OWN token source, never the scan's.</summary>
    public static Task<FolderInspection> InspectPickedFolderAsync(IImportBackend backend, string path, CancellationToken ct) =>
        backend.InspectFolderAsync(path, ct); // MERGE: backend.InspectPickedFolderAsync(path, ct)

    /// <summary>"An import is writing" for as long as the result is alive: the first statement of the apply methods.</summary>
    public static IDisposable EnterRun() => RunMarker.Enter(); // MERGE: ImportRunState.Enter()

    /// <summary>The sentence to show when Import must be off (a launch is starting or an update is running), else null.</summary>
    public static string? Refusal() => null; // MERGE: LaunchRunState.ImportRefusal()

    // Stand-in for ImportRunState.Enter(): the same Begin/End pair, disposed once. Deleted by the merge.
    private sealed class RunMarker : IDisposable
    {
        private int _done;

        public static IDisposable Enter()
        {
            ImportRunState.Begin();
            return new RunMarker();
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _done, 1) == 0) ImportRunState.End();
        }
    }
}
