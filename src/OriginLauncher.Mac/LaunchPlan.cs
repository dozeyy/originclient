using System.Diagnostics;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.Mac;

/// <summary>
/// `--launch-plan &lt;version&gt; [--root &lt;dir&gt;]`: everything Play does for one version on this Mac, short of
/// starting the game. It runs the launcher's own launch path (VersionManager.InstallAndBuildProcessAsync:
/// Fabric or Forge, Origin's mods, Mojang's files, Java, natives) in a scratch data folder, then checks
/// the process it built and prints the Java path, its arch and the argument line with the account and
/// paths redacted. Exit 0 = every check passed, 1 = a check failed, 3 = the version needs Rosetta and this
/// Mac has none. Java is run once with -version to prove it starts on this CPU; Minecraft never starts,
/// and no window opens. `--list-versions` prints every version the picker offers, one per line.
/// </summary>
internal static class LaunchPlan
{
    private const string PlayerName = "OriginPlanner";
    private static readonly List<string> Failures = new();

    // JVM flags naming a folder the game creates while it starts (native-code unpack and temp dirs).
    private static readonly string[] RuntimeScratchFlags =
        ["-Djava.library.path=", "-Djna.tmpdir=", "-Dorg.lwjgl.system.SharedLibraryExtractPath=", "-Dio.netty.native.workdir="];

    public static int ListVersions()
    {
        foreach (var v in VersionCatalog.SupportedVersions) Console.WriteLine(v);
        return 0;
    }

    public static async Task<int> RunAsync(string version, string? root)
    {
        if (!VersionCatalog.IsSupported(version))
        {
            Console.Error.WriteLine($"{version} is not a version the picker offers (see --list-versions).");
            return 2;
        }

        // Never the player's data: everything goes under the scratch root.
        root = Path.GetFullPath(root ?? Path.Combine(Path.GetTempPath(), "origin-launch-plan"));
        OriginPaths.UseScratchRoot(root);

        var shape = PlatformLaunch.ShapeFor(version);
        var mac = MacVersionCapabilities.For(version);
        Console.WriteLine($"Launch plan: Minecraft {version} on macOS {Environment.OSVersion.Version}, machine {PlatformLaunch.HostArch}");
        Console.WriteLine($"  java arch        {shape.JavaArch}{(shape.UnderRosetta ? " (Rosetta 2)" : "")}");
        Console.WriteLine($"  LWJGL            {(shape.Lwjgl2 ? "2" : "3")}");
        Console.WriteLine($"  shaders on Mac   {(mac.ShadersAvailable ? "yes" : "no: " + mac.ShaderNote)}");

        Process process;
        var watch = Stopwatch.StartNew();
        try
        {
            var settings = new LauncherSettings();
            var key = InstanceLayout.KeyFor(null);
            var gameDir = AccountFolders.PrepareGameDir(version, key);
            var (heap, legacy) = await LaunchHeap.PlanAsync(settings, version, gameDir, CancellationToken.None);
            var option = LaunchProfileBuilder.Build(settings, MSession.CreateOfflineSession(PlayerName), version, heap, legacy);
            option.ExtraJvmArguments = (option.ExtraJvmArguments ?? Enumerable.Empty<MArgument>())
                .Append(new MArgument(new GameHandoff().JvmArgument)).ToList();
            process = await new VersionManager().InstallAndBuildProcessAsync(
                version, key, option, externalMods: true, progress: new ConsoleProgress()).ConfigureAwait(false);
        }
        catch (RosettaRequiredException e)
        {
            var failure = PlayFailure.From(e, version);
            Console.WriteLine($"ROSETTA {failure.Title}: {failure.Message} [Install Rosetta: {MacRosetta.InstallCommand}]");
            return 3;
        }
        catch (Exception e)
        {
            Console.WriteLine("FAIL  the launch path threw: " + e);
            return 1;
        }
        Console.WriteLine($"  prepared in      {watch.Elapsed.TotalSeconds:0.0} s");

        var java = process.StartInfo.FileName;
        var args = Tokenize(process.StartInfo.ArgumentList.Count > 0
            ? string.Join(' ', process.StartInfo.ArgumentList.Select(Quote))
            : process.StartInfo.Arguments);

        CheckJava(java, shape);
        CheckArguments(args, shape, root);
        CheckNatives(args, shape);
        CheckAssets(args);

        Console.WriteLine($"  java             {Redact(java, root)}");
        Console.WriteLine($"  working dir      {Redact(process.StartInfo.WorkingDirectory, root)}");
        Console.WriteLine("  arguments        " + string.Join(' ', RedactArgs(args, root).Select(Quote)));
        Console.WriteLine(Failures.Count == 0
            ? $"PASS  {version}: every file is in place for a {shape.JavaArch} launch"
            : $"FAIL  {version}: {Failures.Count} problem(s)");
        return Failures.Count == 0 ? 0 : 1;
    }

    private static void Fail(string what)
    {
        Failures.Add(what);
        Console.WriteLine("FAIL  " + what);
    }

    // The Java binary: on disk, built for the arch the plan wants, and it starts on this Mac (under
    // Rosetta for an x64 Java on Apple Silicon). -version only: no game code runs.
    private static void CheckJava(string java, MacLaunchShape shape)
    {
        if (!File.Exists(java))
        {
            Fail("Java not found: " + java);
            return;
        }
        var archs = MachO.Archs(java);
        var want = shape.JavaArch == MacJavaArch.Arm64 ? MachO.Arm64 : MachO.X64;
        Console.WriteLine($"  java binary      {string.Join('+', archs)}");
        if (!archs.Contains(want)) Fail($"Java is {string.Join('+', archs)}, the plan needs {want}: {java}");

        try
        {
            var info = new ProcessStartInfo(java) { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false };
            info.ArgumentList.Add("-XshowSettings:properties");
            info.ArgumentList.Add("-version");
            using var p = Process.Start(info)!;
            var err = p.StandardError.ReadToEndAsync();
            var outText = p.StandardOutput.ReadToEnd();
            p.WaitForExit(60_000);
            var text = err.Result + outText;
            var osArch = text.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.StartsWith("os.arch", StringComparison.Ordinal));
            var javaVersion = text.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.StartsWith("java.runtime.version", StringComparison.Ordinal));
            Console.WriteLine($"  java runs        exit {p.ExitCode}, {osArch}, {javaVersion}");
            var wantArch = shape.JavaArch == MacJavaArch.Arm64 ? "aarch64" : "x86_64";
            if (p.ExitCode != 0) Fail($"java -version exited {p.ExitCode}");
            else if (osArch == null || !osArch.EndsWith(wantArch, StringComparison.Ordinal)) Fail($"Java reports '{osArch}', expected {wantArch}");
        }
        catch (Exception e)
        {
            Fail("Java could not start: " + e.Message);
        }
    }

    private static void CheckArguments(List<string> args, MacLaunchShape shape, string root)
    {
        // -XstartOnFirstThread: LWJGL 3 needs it on macOS, LWJGL 2 must not get it (MacLaunchPolicy).
        var firstThread = args.Contains(MacLaunchPolicy.StartOnFirstThread);
        if (shape.Lwjgl2 && firstThread) Fail("-XstartOnFirstThread is on an LWJGL 2 launch");
        if (!shape.Lwjgl2 && !firstThread) Fail("-XstartOnFirstThread is missing (LWJGL 3 needs it on macOS)");

        foreach (var placeholder in args.Where(a => a.Contains("${", StringComparison.Ordinal)))
            Fail("unreplaced placeholder in the argument line: " + placeholder);
        foreach (var flag in new[] { "--username", "--version", "--gameDir", "--assetsDir", "--assetIndex", "--accessToken" })
            if (!args.Contains(flag)) Fail("game argument missing: " + flag);
        if (!args.Any(a => a is "net.fabricmc.loader.impl.launch.knot.KnotClient" or "net.minecraft.launchwrapper.Launch"))
            Fail("no Fabric or Forge main class in the argument line");
        if (!args.Any(a => a.StartsWith("-Xmx", StringComparison.Ordinal))) Fail("no -Xmx");

        var cp = args.IndexOf("-cp");
        if (cp < 0 || cp + 1 >= args.Count)
        {
            Fail("no -cp");
            return;
        }
        var entries = args[cp + 1].Split(':', StringSplitOptions.RemoveEmptyEntries);
        var missing = entries.Where(e => !File.Exists(e)).ToList();
        Console.WriteLine($"  classpath        {entries.Length} entries, {missing.Count} missing");
        foreach (var m in missing) Fail("classpath file missing: " + Redact(m, root));
        var lwjgl = entries.Select(Path.GetFileName).Where(n => n!.StartsWith("lwjgl", StringComparison.Ordinal)).Distinct().ToList();
        if (lwjgl.Count == 0) Fail("no LWJGL on the classpath");

        // Every other path the line names (natives, the mods folder, the log config, game/assets dirs) exists.
        // Exception: the scratch folders Java, JNA, LWJGL and Netty unpack native code into at run time
        // (26.x points each at its own subfolder of the natives folder). The game makes them itself and the
        // same launch line works on Windows, so only the version folder above the natives folder has to exist.
        for (var i = 0; i < args.Count; i++)
        {
            if (i == cp + 1) continue;
            var value = args[i].Contains('=') ? args[i][(args[i].IndexOf('=') + 1)..] : args[i];
            if (!value.StartsWith(root, StringComparison.Ordinal) || File.Exists(value) || Directory.Exists(value)) continue;
            var runtimeDir = RuntimeScratchFlags.Any(f => args[i].StartsWith(f, StringComparison.Ordinal));
            if (runtimeDir && Directory.Exists(Path.GetDirectoryName(Path.GetDirectoryName(value.TrimEnd('/')))))
                Console.WriteLine("  made at run time " + Redact(args[i], root));
            else
                Fail("path in the argument line does not exist: " + Redact(args[i], root));
        }

        var mods = args.FirstOrDefault(a => a.StartsWith("-Dfabric.modsFolder=", StringComparison.Ordinal));
        var modsDir = mods?[(mods.IndexOf('=') + 1)..]
            ?? (args.IndexOf("--gameDir") is var g and >= 0 && g + 1 < args.Count ? Path.Combine(args[g + 1], "mods") : null);
        if (modsDir != null && Directory.Exists(modsDir))
            Console.WriteLine($"  mods             {string.Join(", ", Directory.EnumerateFiles(modsDir, "*.jar").Select(Path.GetFileName).Order())}");
    }

    // The native libraries LWJGL loads, for the plan's arch. LWJGL 3.3+ (1.19+) ships them inside
    // natives-macos[-arm64] jars on the classpath and unpacks them itself; older versions get them
    // unpacked by CmlLib into -Djava.library.path. Either way liblwjgl must exist for the right CPU.
    private static void CheckNatives(List<string> args, MacLaunchShape shape)
    {
        var want = shape.JavaArch == MacJavaArch.Arm64 ? MachO.Arm64 : MachO.X64;
        var found = new List<string>();

        var lib = args.FirstOrDefault(a => a.StartsWith("-Djava.library.path=", StringComparison.Ordinal));
        var nativesDir = lib?[(lib.IndexOf('=') + 1)..];
        if (nativesDir != null && Directory.Exists(nativesDir))
            foreach (var file in Directory.EnumerateFiles(nativesDir, "*", SearchOption.AllDirectories)
                         .Where(f => f.EndsWith(".dylib", StringComparison.Ordinal) || f.EndsWith(".jnilib", StringComparison.Ordinal)))
            {
                var archs = MachO.Archs(file);
                if (Path.GetFileName(file).StartsWith("liblwjgl.", StringComparison.Ordinal)) found.AddRange(archs.Select(a => "dir:" + a));
                if (!archs.Contains(want)) Fail($"native {Path.GetFileName(file)} is {string.Join('+', archs)}, needs {want}");
            }

        var cp = args.IndexOf("-cp");
        if (cp >= 0 && cp + 1 < args.Count)
            foreach (var jar in args[cp + 1].Split(':').Where(e => Path.GetFileName(e).Contains("natives-macos", StringComparison.Ordinal) && File.Exists(e)))
            {
                using var zip = ZipFile.OpenRead(jar);
                foreach (var entry in zip.Entries.Where(e => e.Name == "liblwjgl.dylib"))
                {
                    using var s = entry.Open();
                    using var copy = new MemoryStream();
                    s.CopyTo(copy);
                    copy.Position = 0;
                    found.AddRange(MachO.Archs(copy).Select(a => "jar:" + a));
                }
            }

        Console.WriteLine($"  liblwjgl         {(found.Count == 0 ? "none" : string.Join(", ", found.Distinct()))}");
        if (!found.Any(f => f.EndsWith(":" + want, StringComparison.Ordinal)))
            Fail($"no liblwjgl for {want} (natives dir or natives-macos jars)");
    }

    // Every object in the version's asset index is on disk (sounds, language files, the icon).
    private static void CheckAssets(List<string> args)
    {
        var a = args.IndexOf("--assetsDir");
        var i = args.IndexOf("--assetIndex");
        if (a < 0 || i < 0 || a + 1 >= args.Count || i + 1 >= args.Count) return; // reported by CheckArguments
        var index = Path.Combine(args[a + 1], "indexes", args[i + 1] + ".json");
        if (!File.Exists(index))
        {
            Fail("asset index missing: " + args[i + 1]);
            return;
        }
        using var doc = JsonDocument.Parse(File.ReadAllText(index));
        int total = 0, missing = 0;
        foreach (var obj in doc.RootElement.GetProperty("objects").EnumerateObject())
        {
            total++;
            var hash = obj.Value.GetProperty("hash").GetString()!;
            if (!File.Exists(Path.Combine(args[a + 1], "objects", hash[..2], hash))) missing++;
        }
        Console.WriteLine($"  assets           {total} objects, {missing} missing");
        if (missing > 0) Fail($"{missing} of {total} asset objects missing");
    }

    // Account and token values out, scratch paths shortened: the line is safe to paste in a CI log.
    private static readonly HashSet<string> SecretFlags = new(StringComparer.Ordinal)
    {
        "--accessToken", "--uuid", "--username", "--xuid", "--clientId", "--session",
    };

    private static IEnumerable<string> RedactArgs(List<string> args, string root)
    {
        for (var i = 0; i < args.Count; i++)
        {
            var a = args[i];
            if (i > 0 && SecretFlags.Contains(args[i - 1])) yield return "<redacted>";
            else if (a.StartsWith("-D" + GameHandoff.Property + "=", StringComparison.Ordinal)) yield return "-D" + GameHandoff.Property + "=<redacted>";
            else yield return Redact(a, root);
        }
    }

    private static string Redact(string text, string root)
    {
        text = text.Replace(root, "$ROOT", StringComparison.Ordinal);
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return home.Length > 1 ? text.Replace(home, "~", StringComparison.Ordinal) : text;
    }

    private static string Quote(string a) => a.Length > 0 && !a.Any(c => c is ' ' or '"') ? a : "\"" + a.Replace("\"", "\\\"") + "\"";

    // CmlLib hands back one argument string: split it the way the process will, honouring double quotes.
    internal static List<string> Tokenize(string line)
    {
        var list = new List<string>();
        var current = new StringBuilder();
        bool quoted = false, any = false;
        for (var i = 0; i < line.Length; i++)
        {
            var c = line[i];
            if (c == '\\' && i + 1 < line.Length && line[i + 1] == '"') { current.Append('"'); i++; any = true; }
            else if (c == '"') { quoted = !quoted; any = true; }
            else if (c == ' ' && !quoted)
            {
                if (any) list.Add(current.ToString());
                current.Clear();
                any = false;
            }
            else { current.Append(c); any = true; }
        }
        if (any) list.Add(current.ToString());
        return list;
    }

    private sealed class ConsoleProgress : IProgress<string>
    {
        private string? _last;

        public void Report(string value)
        {
            // The file pass reports a counter many times a second; print each stage once.
            var stage = value.Split('(')[0].TrimEnd(' ', '.');
            if (stage == _last) return;
            _last = stage;
            Console.WriteLine("  ... " + value);
        }
    }
}

/// <summary>Reads which CPUs a Mach-O file (thin or universal) is built for, from its header alone.</summary>
internal static class MachO
{
    public const string Arm64 = "arm64";
    public const string X64 = "x86_64";

    public static IReadOnlyList<string> Archs(string path)
    {
        try
        {
            using var s = File.OpenRead(path);
            return Archs(s);
        }
        catch (IOException)
        {
            return new[] { "unreadable" };
        }
    }

    public static IReadOnlyList<string> Archs(Stream s)
    {
        var head = new byte[8];
        if (s.Read(head, 0, 8) < 8) return new[] { "not Mach-O" };
        var magicLe = BitConverter.ToUInt32(head, 0);
        if (magicLe is 0xFEEDFACF or 0xFEEDFACE) return new[] { Cpu(BitConverter.ToInt32(head, 4)) };

        var magicBe = ReadBe(head, 0);
        if (magicBe is 0xCAFEBABE or 0xCAFEBABF)
        {
            var count = (int)ReadBe(head, 4);
            var entry = magicBe == 0xCAFEBABE ? 20 : 32;
            if (count is <= 0 or > 16) return new[] { "not Mach-O" };
            var table = new byte[count * entry];
            if (s.Read(table, 0, table.Length) < table.Length) return new[] { "not Mach-O" };
            return Enumerable.Range(0, count).Select(k => Cpu((int)ReadBe(table, k * entry))).ToList();
        }
        return new[] { "not Mach-O" };
    }

    private static uint ReadBe(byte[] b, int o) => (uint)(b[o] << 24 | b[o + 1] << 16 | b[o + 2] << 8 | b[o + 3]);

    private static string Cpu(int type) => type switch
    {
        0x01000007 => X64,
        0x0100000C => Arm64,
        7 => "i386",
        _ => $"cpu{type:x}",
    };
}
