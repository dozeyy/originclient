using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core.Versions;

public static partial class LaunchPrewarm
{
    // Linux: the warm-up thread's disk priority drops to the idle class (ioprio_set), the closest
    // thing to Windows' THREAD_MODE_BACKGROUND: the kernel serves its reads only when no other I/O
    // waits, so the real install never queues behind it. I/O priority is per thread on Linux
    // (IOPRIO_WHO_PROCESS with id 0 = the calling thread), any user may lower their own, and the
    // thread's CPU side is already ThreadPriority.BelowNormal (Start). glibc has no ioprio_set
    // wrapper, so it goes through syscall(2); an unknown arch or a refusal just means "no change".
    private const int IoprioWhoProcess = 1;
    private const int IoprioClassIdle = 3;
    private const int IoprioClassShift = 13;

    // The syscall numbers differ per architecture (asm/unistd.h): x86_64 251, arm64 30 (the generic table).
    private static readonly long IoprioSetNr = RuntimeInformation.ProcessArchitecture switch
    {
        Architecture.X64 => 251,
        Architecture.Arm64 => 30,
        _ => -1,
    };

    private static readonly long IoprioGetNr = RuntimeInformation.ProcessArchitecture switch
    {
        Architecture.X64 => 252,
        Architecture.Arm64 => 31,
        _ => -1,
    };

    private static bool EnterBackgroundMode() =>
        IoprioSetNr > 0 && syscall(IoprioSetNr, IoprioWhoProcess, 0, IoprioClassIdle << IoprioClassShift) == 0;

    // Class 0 ("none") = follow the CPU nice level again, the thread's default.
    private static void LeaveBackgroundMode()
    {
        if (IoprioSetNr > 0) syscall(IoprioSetNr, IoprioWhoProcess, 0, 0);
    }

    /// <summary>Self-test hook: the calling thread's I/O class (3 = idle), or -1 when it can't be read.</summary>
    internal static int CurrentIoClassForTests() =>
        IoprioGetNr > 0 && syscall(IoprioGetNr, IoprioWhoProcess, 0, 0) is var v and >= 0 ? (int)(v >> IoprioClassShift) : -1;

    /// <summary>Self-test hook: enter and leave the idle class on the calling thread, reporting the class seen inside.</summary>
    internal static (bool Entered, int ClassInside, int ClassAfter) BackgroundRoundTripForTests()
    {
        var entered = EnterBackgroundMode();
        var inside = CurrentIoClassForTests();
        if (entered) LeaveBackgroundMode();
        return (entered, inside, CurrentIoClassForTests());
    }

    // syscall(2) is variadic in C; glibc's is an assembly stub that just moves the registers, so fixed
    // 64-bit arguments are passed exactly as the kernel reads them on both x86_64 and arm64.
    [DllImport("libc", SetLastError = true)]
    private static extern long syscall(long number, long which, long who, long ioprio);
}
