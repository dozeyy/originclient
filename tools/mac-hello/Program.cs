using Avalonia;

namespace MacHello;

// Modes:
//   (no args)                     show the window on the sign-in page; a person can look at it.
//   --probe <dir>                 run the scripted web view proof, log + PNG into <dir>, exit.
//   --keychain set|get <file> ... keychain survival test, no window (KeychainProbe).
public static class Program
{
    public static string? ProbeDir { get; private set; }
    public static int ExitCode { get; set; }

    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Length >= 3 && args[0] == "--keychain")
            return KeychainProbe.Run(args[1], args[2], args.Contains("--allow-prompt"));

        var probe = Array.IndexOf(args, "--probe");
        if (probe >= 0 && probe + 1 < args.Length)
        {
            ProbeDir = args[probe + 1];
            Directory.CreateDirectory(ProbeDir);
            ProbeLog.Open(Path.Combine(ProbeDir, "probe.log"));
        }

        // A silent exit with no RESULT line (seen once under Rosetta) should at least name its exception.
        AppDomain.CurrentDomain.UnhandledException += (_, e) => ProbeLog.Write("UNHANDLED " + e.ExceptionObject);
        TaskScheduler.UnobservedTaskException += (_, e) => ProbeLog.Write("UNOBSERVED " + e.Exception);
        ProbeLog.Write($"START pid={Environment.ProcessId} path={Environment.ProcessPath}");
        ProbeLog.Write($"START arch={System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture} os={Environment.OSVersion.VersionString}");

        var uiExit = AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .LogToTrace()
            .StartWithClassicDesktopLifetime(args);
        return ExitCode != 0 ? ExitCode : uiExit;
    }
}

internal static class ProbeLog
{
    private static StreamWriter? _file;

    public static void Open(string path) => _file = new StreamWriter(path, append: true) { AutoFlush = true };

    public static void Write(string line)
    {
        var stamped = $"{DateTime.UtcNow:HH:mm:ss.fff} {line}";
        Console.WriteLine(stamped);
        // The grep-able lines (CHECK ..., RESULT ...) stay at column 0 in the file.
        _file?.WriteLine(line.StartsWith("CHECK ") || line.StartsWith("RESULT ") ? line : stamped);
    }
}
