using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Import.Sources;

namespace OriginLauncher.App.UI.Controls;

// The places the Import drawer reaches into the import wiring (Core/Import/Launcher), named once so the panel never
// spells the wiring's API itself. The window's close button talks to the panel through the wiring's IImportCloseHost
// (Core/Import/Launcher/ImportCloseGuard.cs), which ImportPanel implements.
internal static class ImportHooks
{
    /// <summary>The versions an import can go into, built on the thread pool by the backend (never on the UI thread).</summary>
    public static Task<IReadOnlyList<string>> TargetsAsync(IImportBackend backend, CancellationToken ct) =>
        backend.GetSupportedTargetsAsync(ct);

    /// <summary>
    /// "Browse for a folder": the backend gives each Browse its own cancellation (a newer Browse replaces it, a running
    /// scan is never cancelled by it); <paramref name="ct"/> only stops this caller waiting (the drawer closing).
    /// </summary>
    public static Task<FolderInspection> InspectPickedFolderAsync(IImportBackend backend, string path, CancellationToken ct) =>
        backend.InspectPickedFolderAsync(path).WaitAsync(ct);

    /// <summary>"An import is writing" for as long as the result is alive: the first statement of the apply methods.</summary>
    public static IDisposable EnterRun() => ImportRunState.Enter();

    /// <summary>The sentence to show when Import must be off (a launch is starting or an update is running), else null.</summary>
    public static string? Refusal() => LaunchRunState.ImportRefusal();
}
