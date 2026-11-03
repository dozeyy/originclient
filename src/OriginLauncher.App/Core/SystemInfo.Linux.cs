using System.IO;

namespace OriginLauncher.App.Core;

// Linux: the kernel's own numbers from procfs (plain text files, ~microseconds, no process to
// spawn). /proc/meminfo's MemTotal is usable physical RAM in kB, the same thing
// GlobalMemoryStatusEx's ullTotalPhys is on Windows and hw.memsize on a Mac.
public static class SystemInfo
{
    public static int GetTotalPhysicalMemoryMb()
    {
        var kb = ProcField("/proc/meminfo", "MemTotal") is { } line ? ParseLeadingLong(line) : null;
        return kb is > 0 ? (int)(kb.Value / 1024) : 8192; // same conservative fallback as Windows
    }

    /// <summary>
    /// The CPU's name: x86's "model name" in /proc/cpuinfo; ARM boards have none there, so the
    /// device-tree model (e.g. "Raspberry Pi 5 Model B Rev 1.0") or the kernel's "Hardware" line
    /// stands in. Null when none is readable.
    /// </summary>
    internal static string? CpuName()
    {
        if (ProcField("/proc/cpuinfo", "model name") is { Length: > 0 } model) return model;
        try
        {
            var dt = "/proc/device-tree/model";
            if (File.Exists(dt) && File.ReadAllText(dt).TrimEnd('\0').Trim() is { Length: > 0 } board) return board;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        return ProcField("/proc/cpuinfo", "Hardware") is { Length: > 0 } hardware ? hardware : null;
    }

    /// <summary>The value after "name:" on the first line of a procfs file that starts with it, or null.</summary>
    internal static string? ProcField(string file, string name)
    {
        try
        {
            foreach (var line in File.ReadLines(file))
            {
                var colon = line.IndexOf(':');
                if (colon > 0 && line.AsSpan(0, colon).Trim().SequenceEqual(name)) return line[(colon + 1)..].Trim();
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        return null;
    }

    private static long? ParseLeadingLong(string value)
    {
        var digits = value.TakeWhile(char.IsAsciiDigit).Count();
        return digits > 0 && long.TryParse(value.AsSpan(0, digits), out var n) ? n : null;
    }
}
