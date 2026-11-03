using System.Runtime.InteropServices;

namespace OriginLauncher.App.Core;

public static class SystemInfo
{
    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX buffer);

    // Real total physical RAM via the OS API — GC.GetGCMemoryInfo() reflects
    // the runtime's configured heap limit, not necessarily true system RAM.
    public static int GetTotalPhysicalMemoryMb()
    {
        var status = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        if (!GlobalMemoryStatusEx(ref status))
            return 8192; // conservative fallback if the API call fails

        return (int)(status.ullTotalPhys / 1024 / 1024);
    }
}
