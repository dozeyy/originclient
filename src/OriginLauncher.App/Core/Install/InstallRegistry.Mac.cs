using System.IO;
using System.Security;

namespace OriginLauncher.App.Core.Install;

// Mac counterpart of the per-user registry entries. Same class and method names as the Windows
// file so shared callers (the Settings "start with your computer" toggle) need no OS checks.
//  - "Start with Windows" = a per-user LaunchAgent plist in ~/Library/LaunchAgents with RunAtLoad:
//    launchd reads that folder at every login, so writing or deleting the file is the whole switch
//    (no launchctl call, no admin, and nothing starts right now). An SMAppService login item would
//    need a Developer ID-signed app; Origin's Mac build is ad-hoc signed.
//  - No Add/Remove Programs exists on a Mac (an app is removed by dragging it to the Bin), so the
//    display-version refresh is a no-op.
public static class InstallRegistry
{
    public const string AppId = "{7E9C1A54-2C3B-4E7A-9C1D-0A1B2C3D4E5F}";
    internal const string AgentLabel = "org.originclient.launcher";

    // The self-test points this at a temp folder; the launcher always uses the real one.
    internal static string LaunchAgentsDir { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Library", "LaunchAgents");

    internal static string AgentPlistPath => Path.Combine(LaunchAgentsDir, AgentLabel + ".plist");

    public static bool IsStartWithWindows() => File.Exists(AgentPlistPath);

    /// <summary>Turns "open Origin when you log in" on (for exePath) or off.</summary>
    public static void SetStartWithWindows(bool enabled, string exePath)
    {
        if (!enabled)
        {
            if (File.Exists(AgentPlistPath)) File.Delete(AgentPlistPath);
            return;
        }
        Directory.CreateDirectory(LaunchAgentsDir);
        // Inside an app bundle, `open -a` starts it the way Finder does (one instance, Dock icon);
        // a bare executable (a dev publish folder) is started directly.
        var bundle = BundleOf(exePath);
        var args = bundle != null ? new[] { "/usr/bin/open", "-a", bundle } : new[] { exePath };
        var argXml = string.Concat(args.Select(a => $"\n        <string>{SecurityElement.Escape(a)}</string>"));
        var plist = $"""
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>{AgentLabel}</string>
    <key>ProgramArguments</key>
    <array>{argXml}
    </array>
    <key>RunAtLoad</key>
    <true/>
</dict>
</plist>

""";
        // Written beside and renamed in, so launchd never reads half a file.
        var temp = AgentPlistPath + ".tmp";
        File.WriteAllText(temp, plist);
        File.Move(temp, AgentPlistPath, overwrite: true);
    }

    /// <summary>No Add/Remove Programs on a Mac: nothing to keep in step.</summary>
    public static void RefreshDisplayVersion(string version)
    {
    }

    // X.app when exePath is X.app/Contents/MacOS/<exe>, else null.
    private static string? BundleOf(string exePath)
    {
        var macOs = Path.GetDirectoryName(exePath);
        var contents = macOs == null ? null : Path.GetDirectoryName(macOs);
        var app = contents == null ? null : Path.GetDirectoryName(contents);
        return app != null && Path.GetFileName(macOs) == "MacOS" && Path.GetFileName(contents) == "Contents"
               && app.EndsWith(".app", StringComparison.OrdinalIgnoreCase) ? app : null;
    }
}
