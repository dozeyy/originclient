using OriginLauncher.App.Core.Install;

namespace OriginSetup.Engine;

// Removes Origin: shortcuts, Start-with-Windows, the Add/Remove Programs
// entry and the install folder — and, only when the player ticks it, their
// Origin data (accounts, settings, game instances with their worlds).
public static class Uninstaller
{
    /// <summary>Returns false when something could not be fully removed (it is logged).</summary>
    public static bool Run(string dir, bool removeUserData, Action<double> report)
    {
        SetupLog.Write($"uninstall {dir} (removeUserData={removeUserData})");
        report(0.05);
        LauncherProcess.Close(TimeSpan.FromSeconds(5));

        report(0.2);
        Shortcuts.Delete(Shortcuts.StartMenuPath);
        Shortcuts.Delete(Shortcuts.DesktopPath);
        Shortcuts.DeleteLegacyGroup();
        try { InstallRegistry.SetStartWithWindows(false, ""); }
        catch (Exception ex) when (ex is UnauthorizedAccessException || ex is System.Security.SecurityException) { }
        Registration.Remove();

        report(0.4);
        bool ok = true;
        if (InstallLocation.SafeToDelete(dir))
            ok &= FileOps.TryDeleteTree(dir);
        else
            SetupLog.Write($"not deleting {dir}: not recognisably Origin's own folder");

        report(0.75);
        if (removeUserData)
            ok &= FileOps.TryDeleteTree(Handoff.DataRoot);

        SetupLog.Write(ok ? "uninstall complete" : "uninstall finished with leftovers");
        report(1);
        return ok;
    }
}
