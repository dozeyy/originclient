using System.Runtime.InteropServices;
using System.Text;

namespace OriginLauncher.App.Core;

// Mac: the kernel's own numbers through sysctlbyname (libSystem, always present, ~microseconds,
// no process to spawn). hw.memsize is total physical RAM in bytes, the same thing
// GlobalMemoryStatusEx's ullTotalPhys is on Windows.
public static class SystemInfo
{
    [DllImport("libc", SetLastError = true)]
    private static extern int sysctlbyname(string name, IntPtr oldp, ref IntPtr oldlenp, IntPtr newp, IntPtr newlen);

    public static int GetTotalPhysicalMemoryMb()
    {
        long? bytes;
        // No libc = not a Mac (the Mac project's headless --render-shots run on a Windows dev PC): the fallback.
        try { bytes = SysctlInt64("hw.memsize"); }
        catch (DllNotFoundException) { bytes = null; }
        return bytes is > 0 ? (int)(bytes.Value / 1024 / 1024) : 8192; // same conservative fallback as Windows
    }

    /// <summary>A 64-bit sysctl value, or null when the name is unknown or the call fails.</summary>
    internal static long? SysctlInt64(string name)
    {
        // The Mac build's headless render shots also run on a Windows PC, where there is no libc to call.
        if (!OperatingSystem.IsMacOS()) return null;
        var size = (IntPtr)sizeof(long);
        var buffer = Marshal.AllocHGlobal(sizeof(long));
        try
        {
            Marshal.WriteInt64(buffer, 0);
            if (sysctlbyname(name, buffer, ref size, IntPtr.Zero, IntPtr.Zero) != 0) return null;
            return size == (IntPtr)sizeof(int) ? Marshal.ReadInt32(buffer) : Marshal.ReadInt64(buffer);
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    /// <summary>A string sysctl value (e.g. machdep.cpu.brand_string), or null.</summary>
    internal static string? SysctlString(string name)
    {
        if (!OperatingSystem.IsMacOS()) return null;
        var size = IntPtr.Zero;
        if (sysctlbyname(name, IntPtr.Zero, ref size, IntPtr.Zero, IntPtr.Zero) != 0 || size == IntPtr.Zero || (long)size > 4096)
            return null;
        var buffer = Marshal.AllocHGlobal(size);
        try
        {
            if (sysctlbyname(name, buffer, ref size, IntPtr.Zero, IntPtr.Zero) != 0) return null;
            var bytes = new byte[(int)size];
            Marshal.Copy(buffer, bytes, 0, bytes.Length);
            return Encoding.UTF8.GetString(bytes).TrimEnd('\0').Trim();
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }
}
