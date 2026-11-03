namespace OriginLauncher.App.Core.Versions;

public static partial class LaunchPrewarm
{
    // Mac: no thread-priority call. The thread is already ThreadPriority.BelowNormal (Start), the walk
    // is capped and cancelled the moment a launch starts, and macOS has no per-thread "background
    // I/O" switch like Windows' THREAD_MODE_BACKGROUND (setiopolicy_np is per process, and lowering
    // the whole launcher's disk priority would slow the real install).
    private static bool EnterBackgroundMode() => false;

    private static void LeaveBackgroundMode()
    {
    }
}
