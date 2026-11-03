using System.Diagnostics;
using System.IO;

namespace OriginLauncher.App.Core.Launch;

/// <summary>What happened when the player asked for Rosetta: installed, or why not (shown under the button).</summary>
public sealed record MacRosettaInstallResult(bool Installed, string Detail);

/// <summary>
/// Rosetta 2 on Apple Silicon: is it installed, and install it when the player clicks "Install Rosetta"
/// (never on its own). x64 Java exec'd without Rosetta fails with "Bad CPU type in executable", so
/// PlatformLaunch.EnsureCanRun asks first and throws RosettaRequiredException instead.
/// </summary>
public static class MacRosetta
{
    /// <summary>The exact command the button runs. The licence flag is the player's click on "Install".</summary>
    public const string InstallCommand = "softwareupdate --install-rosetta --agree-to-license";

    private static volatile bool _known;

    /// <summary>
    /// True when x64 programs run on this Mac: an Intel Mac, or Apple Silicon with Rosetta installed. The
    /// test is the real one, `arch -x86_64 /usr/bin/true` (exit 0 only when Rosetta can translate);
    /// a yes is cached for the process, a no is asked again (the player may install it meanwhile).
    /// ~20 ms, so callers stay off the UI thread (the launch path already is).
    /// </summary>
    public static bool IsInstalled()
    {
        if (_known || PlatformLaunch.HostArch != System.Runtime.InteropServices.Architecture.Arm64) return true;
        try
        {
            using var probe = Process.Start(new ProcessStartInfo("/usr/bin/arch", "-x86_64 /usr/bin/true")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            });
            if (probe == null) return false;
            if (!probe.WaitForExit(10_000))
            {
                try { probe.Kill(); } catch (InvalidOperationException) { /* already exited */ }
                return false;
            }
            return _known = probe.ExitCode == 0;
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or IOException or InvalidOperationException)
        {
            return false;
        }
    }

    /// <summary>
    /// Runs Apple's own installer (<see cref="InstallCommand"/>) and checks the result with
    /// <see cref="IsInstalled"/>. Call it only from the player's click. Never throws; a failure comes back
    /// with the line to show (most often: no internet, or the account is not an administrator).
    /// </summary>
    public static async Task<MacRosettaInstallResult> InstallAsync(CancellationToken ct = default)
    {
        if (IsInstalled()) return new MacRosettaInstallResult(true, "Rosetta is installed.");
        try
        {
            var start = new ProcessStartInfo("/usr/sbin/softwareupdate")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            start.ArgumentList.Add("--install-rosetta");
            start.ArgumentList.Add("--agree-to-license");
            using var install = Process.Start(start);
            if (install == null) return new MacRosettaInstallResult(false, "Couldn't start Apple's installer.");
            var output = install.StandardOutput.ReadToEndAsync(ct);
            var error = install.StandardError.ReadToEndAsync(ct);
            await install.WaitForExitAsync(ct).ConfigureAwait(false);
            var text = ((await output.ConfigureAwait(false)) + " " + (await error.ConfigureAwait(false))).Trim();
            if (IsInstalled()) return new MacRosettaInstallResult(true, "Rosetta is installed.");
            return new MacRosettaInstallResult(false,
                "Rosetta didn't install. Check your internet connection, or run this in Terminal: " + InstallCommand
                + (text.Length > 0 ? " (" + LastLine(text) + ")" : ""));
        }
        catch (OperationCanceledException)
        {
            return new MacRosettaInstallResult(false, "Cancelled.");
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or IOException or InvalidOperationException)
        {
            return new MacRosettaInstallResult(false, "Couldn't start Apple's installer: " + ex.Message);
        }
    }

    private static string LastLine(string text)
    {
        var lines = text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return lines.Length == 0 ? text : lines[^1];
    }
}
