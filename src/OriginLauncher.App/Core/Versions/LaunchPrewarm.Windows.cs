using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Versions;

public static partial class LaunchPrewarm
{
    // THREAD_MODE_BACKGROUND_BEGIN: the OS lowers this thread's disk and memory priority too.
    private static bool EnterBackgroundMode() => SetThreadPriority(GetCurrentThread(), ThreadModeBackgroundBegin);

    private static void LeaveBackgroundMode() => SetThreadPriority(GetCurrentThread(), ThreadModeBackgroundEnd);

    private const uint ThreadModeBackgroundBegin = 0x00010000;
    private const uint ThreadModeBackgroundEnd = 0x00020000;

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentThread();

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetThreadPriority(IntPtr thread, uint priority);
}
