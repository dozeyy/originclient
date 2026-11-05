using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using CmlLib.Core;
using CmlLib.Core.ProcessBuilder;

namespace OriginLauncher.App.Core.Launch;

// Windows: CmlLib's defaults (LauncherOSRule.Current, Mojang's JSON arguments) are the launch, unchanged.
public static partial class PlatformLaunch
{
    public static partial void EnsureCanRun(string version) { }

    // The words the crash report uses for this computer (CrashRules): the system, the machine, where drivers come from.
    public static string SystemName => "Windows";
    public static string MachineName => "PC";
    public static string DriverUpdateAdvice => "Update your graphics driver (NVIDIA, AMD or Intel, or Windows Update)";

    public static partial void Configure(MinecraftLauncher launcher, string version) { }

    public static partial void AdjustOption(string version, MLaunchOption option) { }

    // A data folder Java can't spell in this PC's ANSI code page (a user name like "Şükrü" on English Windows): the
    // launch uses its 8.3 short name instead, and Java's temp folder (TMP/TEMP, which java.io.tmpdir reads) moves to a
    // short-named folder under it too. Unchanged for every other PC.
    static partial void AfterBuildCore(System.Diagnostics.Process built)
    {
        var root = OriginPaths.Root;
        var (need, shortRoot) = JavaPathEncoding.Decide(root, (int)GetACP(), ShortPathOf);
        if (need == JavaPathEncoding.Need.None) return;
        if (need == JavaPathEncoding.Need.Impossible || shortRoot == null) throw new JavaPathUnreadableException(root);

        var psi = built.StartInfo;
        JavaPathEncoding.Apply(psi, root, shortRoot);
        try
        {
            Directory.CreateDirectory(OriginPaths.JavaTemp);
            if (ShortPathOf(OriginPaths.JavaTemp) is { } shortTemp && JavaPathEncoding.Representable(shortTemp, (int)GetACP()))
            {
                psi.Environment["TMP"] = shortTemp;
                psi.Environment["TEMP"] = shortTemp;
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Java keeps the user's own temp folder: the game files themselves are already on short paths.
        }
    }

    [DllImport("kernel32.dll")]
    private static extern uint GetACP();

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetShortPathNameW(string longPath, StringBuilder shortPath, uint size);

    // The 8.3 name of an existing folder, or null (the drive keeps none, or the call failed).
    private static string? ShortPathOf(string path)
    {
        var buffer = new StringBuilder(1024);
        var length = GetShortPathNameW(path, buffer, (uint)buffer.Capacity);
        if (length == 0) return null;
        if (length > buffer.Capacity)
        {
            buffer = new StringBuilder((int)length);
            length = GetShortPathNameW(path, buffer, (uint)buffer.Capacity);
            if (length == 0 || length > buffer.Capacity) return null;
        }
        return buffer.ToString();
    }
}
