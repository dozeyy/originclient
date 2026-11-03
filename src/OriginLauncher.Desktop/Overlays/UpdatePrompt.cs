using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Overlays;

// STUB: replaced by the update port (check, download, the top bar chip, restart into the update).
public static class UpdatePrompt
{
    public static void Open(MainWindow window) => window.Toast("Updates are not wired yet");

    /// <summary>Starts the update check once the window is up (real runs only).</summary>
    public static void StartChecks(MainWindow window) => _ = window;
}
