using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;

namespace OriginLauncher.App.Core.Updates;

// Windows: the win-x64 zip, swapped in by a cmd script after this process exits (a running exe
// can't overwrite itself), and WPF's own shutdown.
public static partial class UpdateService
{
    private const string ReleaseAssetName = AssetName;
    private const string ReleaseZipPrefix = "OriginLauncher-win-x64";

    // Windows takes the small update (UpdateDelta): the install folder is a plain folder of files, so the stage can be
    // rebuilt from it file by file. tools/release/delta.py builds these deltas for the win-x64 zip only.
    private static readonly bool DeltaUpdates = true;

    private static void StartApplyScript(string stageDir, string updatesRoot)
    {
        var appDir = InstalledAppDir;
        var exePath = Environment.ProcessPath
                      ?? Path.Combine(appDir, "OriginLauncher.App.exe");
        var pid = Environment.ProcessId;

        // Paths travel in the script's environment, never in its text (WindowsApplyScript): a non-English letter or a
        // "%" in the user folder no longer breaks the swap.
        Process.Start(WindowsApplyScript.Prepare(updatesRoot, pid, stageDir, appDir, exePath));
    }

    private static void ShutdownApp() => Application.Current.Shutdown();

    // Nothing refuses an update up front on Windows: the swap script copies into the install folder after the exit.
    private static Task<string?> InstallBlockedHereAsync() => Task.FromResult<string?>(null);

    private static void ExtractUpdate(string zipPath, string stageDir) =>
        System.IO.Compression.ZipFile.ExtractToDirectory(zipPath, stageDir, overwriteFiles: true);

    // The background download waits on a metered connection (Settings > Network > "Metered connection", a phone
    // hotspot, roaming): Windows' own network cost, read through the Network List Manager (no WinRT needed). Any
    // failure reading it counts as unmetered; the Update click itself always downloads.
    static partial void ReadMeteredOverride(ref bool metered)
    {
        object? manager = null;
        try
        {
            manager = Activator.CreateInstance(Type.GetTypeFromCLSID(NetworkListManagerClsid, throwOnError: true)!);
            ((INetworkCostManager)manager!).GetCost(out var cost, IntPtr.Zero);
            metered = UpdatePrefetch.IsMeteredCost(cost);
        }
        catch (Exception)
        {
            // No Network List Manager answer: unmetered.
        }
        finally
        {
            if (manager != null && Marshal.IsComObject(manager)) Marshal.ReleaseComObject(manager);
        }
    }

    private static readonly Guid NetworkListManagerClsid = new("DCB00C01-570F-4A9B-8D69-199FDBA5723B");

    // netlistmgr.h. Only GetCost is called; the vtable order must still match, so the later methods are declared.
    [ComImport, Guid("DCB00008-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface INetworkCostManager
    {
        void GetCost(out uint cost, IntPtr destIpAddress);
        void GetDataPlanStatus(IntPtr dataPlanStatus, IntPtr destIpAddress);
        void SetDestinationAddresses(uint length, IntPtr destIpAddressList, [MarshalAs(UnmanagedType.VariantBool)] bool append);
    }
}
