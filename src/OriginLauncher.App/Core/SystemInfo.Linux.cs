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
    /// stands in, and on ARM machines with neither (ACPI servers and VMs, e.g. GitHub's arm64
    /// runners) the core is named from its "CPU implementer" / "CPU part" ids. Null when none is readable.
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
        if (ProcField("/proc/cpuinfo", "Hardware") is { Length: > 0 } hardware) return hardware;
        return ArmCoreName(ProcField("/proc/cpuinfo", "CPU implementer"), ProcField("/proc/cpuinfo", "CPU part"));
    }

    /// <summary>
    /// Names an ARM core from the ids the kernel prints ("0x41", "0xd0c" = Arm Neoverse-N1). Only the
    /// common Arm Ltd cores are named; any other ARM core is "ARM CPU". Null when there are no ids.
    /// </summary>
    internal static string? ArmCoreName(string? implementer, string? part)
    {
        if (string.IsNullOrEmpty(implementer)) return null;
        if (!implementer.Equals("0x41", StringComparison.OrdinalIgnoreCase)) return "ARM CPU";
        var core = part?.ToLowerInvariant() switch
        {
            "0xd03" => "Cortex-A53", "0xd05" => "Cortex-A55", "0xd07" => "Cortex-A57", "0xd08" => "Cortex-A72",
            "0xd09" => "Cortex-A73", "0xd0a" => "Cortex-A75", "0xd0b" => "Cortex-A76", "0xd0d" => "Cortex-A77",
            "0xd41" => "Cortex-A78", "0xd44" => "Cortex-X1", "0xd46" => "Cortex-A510", "0xd47" => "Cortex-A710",
            "0xd48" => "Cortex-X2", "0xd4d" => "Cortex-A715", "0xd4e" => "Cortex-X3", "0xd80" => "Cortex-A520",
            "0xd81" => "Cortex-A720", "0xd82" => "Cortex-X4",
            "0xd0c" => "Neoverse-N1", "0xd40" => "Neoverse-V1", "0xd49" => "Neoverse-N2", "0xd4f" => "Neoverse-V2",
            _ => null,
        };
        return core == null ? "Arm CPU" : "Arm " + core;
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
