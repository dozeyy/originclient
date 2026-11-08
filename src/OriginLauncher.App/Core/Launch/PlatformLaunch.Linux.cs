using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Launch;

// Linux: CmlLib's defaults (LauncherOSRule.Current, Mojang's JSON arguments) are the launch, unchanged, as on Windows.
public static partial class PlatformLaunch
{
    // 1.8.9's LWJGL 2.9.4 lists screen modes by running the `xrandr` program (org.lwjgl.opengl.XRandR); with no xrandr
    // it gets an empty list and LinuxDisplay.getAvailableDisplayModes throws ArrayIndexOutOfBoundsException: 0 before the
    // game window exists. AppImage/tar.gz players and minimal desktops often lack it (the .deb/.rpm only recommend it),
    // so Play says what to install instead of starting a game that can only crash. LWJGL 3 (every Fabric version) uses
    // libXrandr directly and never needs the program. CrashRules' "linux-xrandr" rule covers a crash that gets past this.
    public static partial void EnsureCanRun(string version)
    {
        if (MacLaunchPolicy.UsesLwjgl2(version) && !LinuxPackages.OnPath("xrandr"))
            throw new SystemPieceMissingException(version, LinuxPackages.Xrandr,
                LinuxPackages.InstallCommand(LinuxPackages.Xrandr, LinuxPackages.ThisComputer));
    }

    // The words the crash report uses for this computer (CrashRules): the system, the machine, where drivers come from.
    public static string SystemName => "Linux";
    public static string MachineName => "PC";
    public static string DriverUpdateAdvice => "Update your graphics driver (Mesa, or your distro's NVIDIA driver)";

    public static partial void Configure(MinecraftLauncher launcher, string version) { }

    public static partial void AdjustOption(string version, MLaunchOption option) { }
}
