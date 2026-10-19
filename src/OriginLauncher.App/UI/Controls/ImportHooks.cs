using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;

namespace OriginLauncher.App.UI.Controls;

// The three places the Import drawer reaches into the import wiring. The wiring agent's versions of these
// (branch claude/import-wiring) are not on this branch's base, so each is written against the signature the
// wiring review describes, in ONE place: when that branch is merged, only the marked line in each method changes.
internal static class ImportHooks
{
    /// <summary>
    /// The Minecraft versions an import can go into. Never read <see cref="IImportBackend.SupportedTargets"/> on the UI
    /// thread (the real backend can build its whole engine for it): this runs the read off it.
    /// WIRING: becomes `backend.GetSupportedTargetsAsync(ct)`.
    /// </summary>
    public static Task<IReadOnlyList<string>> TargetsAsync(IImportBackend backend, CancellationToken ct) =>
        Task.Run(() => backend.SupportedTargets, ct);

    /// <summary>
    /// "Browse for a folder": works out what the picked folder is. The caller passes its OWN token source, never the scan's.
    /// WIRING: becomes `backend.InspectPickedFolderAsync(path, ct)`.
    /// </summary>
    public static Task<FolderInspection> InspectPickedFolderAsync(IImportBackend backend, string path, CancellationToken ct) =>
        backend.InspectFolderAsync(path, ct);

    /// <summary>
    /// Marks "an import is writing" (Home keeps Play and Join off) for as long as the result is alive:
    /// `using var run = ImportHooks.EnterRun();` is the first statement of the method that applies.
    /// WIRING: becomes `ImportRunState.Enter()`.
    /// </summary>
    public static IDisposable EnterRun()
    {
        ImportRunState.Begin();
        return new Run();
    }

    private sealed class Run : IDisposable
    {
        private int _done;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _done, 1) == 0) ImportRunState.End();
        }
    }
}
