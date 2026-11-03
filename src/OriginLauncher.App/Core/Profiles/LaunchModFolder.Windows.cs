using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Profiles;

public static partial class LaunchModFolder
{
    // A second name for the same file (NTFS hard link, same volume). False when it can't be made: copy instead.
    private static bool TryHardLink(string link, string existing) =>
        OperatingSystem.IsWindows() && CreateHardLinkW(link, existing, IntPtr.Zero);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateHardLinkW(string lpFileName, string lpExistingFileName, IntPtr lpSecurityAttributes);
}
