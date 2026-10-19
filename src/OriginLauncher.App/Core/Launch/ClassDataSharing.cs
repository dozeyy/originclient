using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace OriginLauncher.App.Core.Launch;

// Class-data sharing (CDS) for the game's JVM: a faster boot, nothing else.
//
// Why: Mojang's Java runtimes ship the JDK's default class list (lib/classlist)
// but NOT the archive built from it (bin\server\classes.jsa next to jvm.dll; checked on
// java-runtime-delta, Microsoft OpenJDK 21.0.7, 2026-10-19). So every player's
// game loads, parses and verifies the JDK's own classes from scratch on every
// boot, which a stock JDK never does. The launcher builds that archive once per
// runtime with the runtime's own `java -Xshare:dump` (~0.5 s, ~13 MB) and points
// the game at it with -XX:SharedArchiveFile. Measured with the boot-time
// workflow: see JvmArgPresets' note and MEMORY.md (2026-10-19 faster boot).
//
// Safe by construction:
//  * The game only READS the archive. A missing, stale (runtime updated),
//    corrupt or mismatched one (e.g. a 32+ GB heap turns compressed oops off)
//    is ignored by the JVM, which boots exactly as before (-Xshare:auto, the
//    default; probed: exit code 0 in every case).
//  * The game never WRITES one. A JVM that fails to write an archive at exit
//    exits with code 1 (probed: -XX:+AutoCreateSharedArchive on a locked file),
//    which would show the player a false crash, so the dynamic
//    AutoCreateSharedArchive / ArchiveClassesAtExit modes are not used here.
//  * The archive is built in a separate short-lived JVM, written to a temp name
//    and renamed into place, so a game never sees a half-written file, and two
//    launches at once never write the same file.
//  * Any failure (no jvm.dll, dump fails or times out, disk full) just means
//    this launch has no flag.
public static class ClassDataSharing
{
    // Versions whose boot was measured with it (boot-time workflow). The flag
    // works on any Java 9+ runtime; add a version here once it is measured.
    private static readonly HashSet<string> Versions = ["1.21.1"];

    // A dump takes ~0.5 s on a fast PC; this only stops a wedged one.
    private static readonly TimeSpan DumpTimeout = TimeSpan.FromSeconds(60);

    // One dump at a time per launcher (two accounts launching together).
    private static readonly SemaphoreSlim DumpGate = new(1, 1);

    public const string ArchiveOption = "-XX:SharedArchiveFile=";

    /// <summary>True when this version's launches get the archive.</summary>
    public static bool IsEnabledFor(string? version) => version != null && Versions.Contains(version);

    /// <summary>
    /// True when the player already decides CDS for this launch: "Replace Origin's
    /// tuned defaults" is on, or their own JVM arguments name a CDS option.
    /// </summary>
    public static bool PlayerOverrides(bool replacePresets, string? customArguments) =>
        replacePresets
        || (customArguments != null
            && (customArguments.Contains("-Xshare", StringComparison.OrdinalIgnoreCase)
                || customArguments.Contains("SharedArchiveFile", StringComparison.OrdinalIgnoreCase)
                || customArguments.Contains("ArchiveClassesAtExit", StringComparison.OrdinalIgnoreCase)
                || customArguments.Contains("AutoCreateSharedArchive", StringComparison.OrdinalIgnoreCase)));

    /// <summary>
    /// The archive's file name for one runtime build: changes whenever the
    /// runtime's JVM changes (Mojang updated the runtime), so an old archive is
    /// never pointed at by a new JVM (which would ignore it anyway).
    /// </summary>
    public static string ArchiveName(string javaHome, long jvmLength, DateTime jvmWriteUtc)
    {
        var id = $"{Path.GetFullPath(javaHome).TrimEnd('\\', '/').ToLowerInvariant()}|{jvmLength}|{jvmWriteUtc.Ticks}";
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(id)))[..16].ToLowerInvariant();
        return $"base-{hash}.jsa";
    }

    /// <summary>
    /// <paramref name="arguments"/> with the archive option first (JVM options
    /// must come before the main class). Quoted like CmlLib quotes an argument
    /// with spaces, so a Windows user name with a space still works.
    /// </summary>
    public static string WithArchive(string arguments, string archivePath)
    {
        var option = ArchiveOption + archivePath;
        if (option.Any(char.IsWhiteSpace)) option = "\"" + option + "\"";
        return string.IsNullOrEmpty(arguments) ? option : option + " " + arguments;
    }

    /// <summary>
    /// Adds the archive option to a built game process when this version has it
    /// and the player hasn't taken over CDS, building the archive first if this
    /// runtime has none yet (once per runtime). Never throws: any problem means
    /// a launch without the option. Call off the UI thread.
    /// </summary>
    public static async Task ApplyAsync(ProcessStartInfo startInfo, string version,
        bool replacePresets, string? customArguments, CancellationToken ct)
    {
        try
        {
            if (!IsEnabledFor(version) || PlayerOverrides(replacePresets, customArguments)) return;
            var archive = await EnsureArchiveAsync(startInfo.FileName, ct);
            if (archive != null) startInfo.Arguments = WithArchive(startInfo.Arguments, archive);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // the launch itself was cancelled (a newer launch of this account)
        }
        catch
        {
            // Boot without the archive.
        }
    }

    // The archive for the runtime java at javaPath (…\bin\java.exe or javaw.exe),
    // building it if missing. Null when this runtime can't have one or already
    // ships its own (a JVM uses the classes.jsa next to its jvm.dll by itself).
    private static async Task<string?> EnsureArchiveAsync(string javaPath, CancellationToken ct)
    {
        var bin = Path.GetDirectoryName(javaPath);
        var home = bin == null ? null : Path.GetDirectoryName(bin);
        if (home == null) return null;
        var jvm = new FileInfo(Path.Combine(home, "bin", "server", "jvm.dll"));
        if (!jvm.Exists) return null;
        if (File.Exists(Path.Combine(jvm.DirectoryName!, "classes.jsa"))) return null;
        if (!File.Exists(Path.Combine(home, "lib", "classlist"))) return null;

        var dir = OriginPaths.ClassDataSharing;
        var archive = Path.Combine(dir, ArchiveName(home, jvm.Length, jvm.LastWriteTimeUtc));
        if (IsUsable(archive)) return archive;

        await DumpGate.WaitAsync(ct);
        try
        {
            if (IsUsable(archive)) return archive; // another launch just built it
            Directory.CreateDirectory(dir);
            SweepTemps(dir);
            var temp = archive + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                if (!await DumpAsync(javaPath, temp, ct) || !IsUsable(temp)) return null;
                try { File.Move(temp, archive); }
                catch (IOException) when (IsUsable(archive)) { /* built meanwhile by another launcher process */ }
                return archive;
            }
            finally
            {
                try { if (File.Exists(temp)) File.Delete(temp); } catch { /* swept by a later dump */ }
            }
        }
        finally
        {
            DumpGate.Release();
        }
    }

    // Temp files a killed launcher left behind (an hour old: never a live dump's).
    private static void SweepTemps(string dir)
    {
        try
        {
            foreach (var file in Directory.EnumerateFiles(dir, "*.tmp"))
            {
                try
                {
                    if (DateTime.UtcNow - File.GetLastWriteTimeUtc(file) > TimeSpan.FromHours(1)) File.Delete(file);
                }
                catch { /* in use or gone */ }
            }
        }
        catch { /* nothing to sweep */ }
    }

    private static bool IsUsable(string path)
    {
        try { return new FileInfo(path) is { Exists: true, Length: > 0 }; }
        catch { return false; }
    }

    // `java -Xshare:dump` from the runtime's default class list, into temp.
    private static async Task<bool> DumpAsync(string javaPath, string temp, CancellationToken ct)
    {
        var info = new ProcessStartInfo
        {
            FileName = javaPath,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        info.ArgumentList.Add("-Xshare:dump");
        info.ArgumentList.Add(ArchiveOption + temp);

        using var process = new Process { StartInfo = info };
        process.OutputDataReceived += (_, _) => { };
        process.ErrorDataReceived += (_, _) => { };
        if (!process.Start()) return false;
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(DumpTimeout);
        try
        {
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { /* already gone */ }
            ct.ThrowIfCancellationRequested();
            return false; // timed out
        }
        return process.ExitCode == 0;
    }
}
