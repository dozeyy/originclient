using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Profiles;

public static partial class LaunchModFolder
{
    // A second name for the same file: POSIX link(2) from libc (ext4, btrfs, xfs all support file hard
    // links within one filesystem). link() never replaces an existing name; Sync deletes the temp name
    // first. False when it can't be made (another filesystem, FAT/exFAT, some FUSE mounts): copy instead.
    // Same call as LaunchModFolder.Mac.cs; a separate file only because that one is guarded to macOS.
    private static bool TryHardLink(string link, string existing) =>
        OperatingSystem.IsLinux() && LibcLink(existing, link) == 0;

    // libc's argument order is (existing, new), the reverse of CreateHardLinkW.
    [DllImport("libc", EntryPoint = "link", SetLastError = true)]
    private static extern int LibcLink([MarshalAs(UnmanagedType.LPUTF8Str)] string existing, [MarshalAs(UnmanagedType.LPUTF8Str)] string newName);

    /// <summary>Self-test hook: the real link call.</summary>
    internal static bool TryHardLinkForTests(string link, string existing) => TryHardLink(link, existing);
}
