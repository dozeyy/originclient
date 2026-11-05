using System.Diagnostics;
using System.Text;

namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Java on Windows reads its command line through the ANSI code page (the JDK launcher's main() gets char* argv;
/// JDK-4488646, won't fix). A letter that code page can't spell - "Şükrü" on an English PC (1252), "Дима" on a German
/// one, any emoji - arrives as "?" or a best-fit lookalike, so -cp, java.library.path, fabric.modsFolder, --gameDir
/// and java.exe's own location point at folders that don't exist: the game never starts, and its "Could not find or
/// load main class" read as damaged files with a Repair that can't help (LOC-2).
///
/// The fix hands Java the data folder's 8.3 short name (C:\Users\SKR~1\...: plain ASCII, on by default on C:) in every
/// path of the launch. When the folder has no short name, Play stops before Java starts with a sentence that says
/// what is wrong (<see cref="JavaPathUnreadableException"/>), never the damaged-files card. Pure (the code page and
/// the short-name lookup are parameters) so the decision is unit-tested on every OS; PlatformLaunch.Windows wires it.
/// </summary>
public static class JavaPathEncoding
{
    public enum Need { None, ShortPath, Impossible }

    static JavaPathEncoding() => Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);

    /// <summary>
    /// True when every character of <paramref name="text"/> survives the trip through <paramref name="codePage"/>
    /// exactly. Strict on purpose: Windows' own conversion is best-fit ("Ş" becomes "S"), which still names a folder
    /// that doesn't exist.
    /// </summary>
    public static bool Representable(string text, int codePage)
    {
        Encoding encoding;
        try
        {
            encoding = Encoding.GetEncoding(codePage, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException)
        {
            return true; // a code page .NET doesn't know: leave the launch as it always was
        }
        try
        {
            return encoding.GetString(encoding.GetBytes(text)) == text;
        }
        catch (Exception ex) when (ex is EncoderFallbackException or DecoderFallbackException)
        {
            return false;
        }
    }

    /// <summary>
    /// What the launch needs for a data folder at <paramref name="root"/>: nothing (Java can read it), the short
    /// name <paramref name="shortPathOf"/> returns (when Java can read THAT), or Impossible.
    /// </summary>
    public static (Need Need, string? ShortRoot) Decide(string root, int codePage, Func<string, string?> shortPathOf)
    {
        if (Representable(root, codePage)) return (Need.None, null);
        string? shortRoot;
        try
        {
            shortRoot = shortPathOf(root);
        }
        catch (Exception)
        {
            shortRoot = null;
        }
        return !string.IsNullOrEmpty(shortRoot) && Representable(shortRoot, codePage)
            ? (Need.ShortPath, shortRoot.TrimEnd('\\', '/'))
            : (Need.Impossible, null);
    }

    /// <summary>
    /// Points the built launch at <paramref name="shortRoot"/> instead of <paramref name="longRoot"/>: java's own path,
    /// every argument and the working folder. The two name the same folder, so nothing else changes.
    /// </summary>
    public static void Apply(ProcessStartInfo psi, string longRoot, string shortRoot)
    {
        longRoot = longRoot.TrimEnd('\\', '/');
        psi.FileName = Swap(psi.FileName, longRoot, shortRoot);
        psi.WorkingDirectory = Swap(psi.WorkingDirectory, longRoot, shortRoot);
        if (!string.IsNullOrEmpty(psi.Arguments)) psi.Arguments = Swap(psi.Arguments, longRoot, shortRoot);
        for (var i = 0; i < psi.ArgumentList.Count; i++)
            psi.ArgumentList[i] = Swap(psi.ArgumentList[i], longRoot, shortRoot);
    }

    // Windows paths are case-insensitive, and CmlLib may print the root as it was given or as the file system has it.
    private static string Swap(string text, string longRoot, string shortRoot) =>
        string.IsNullOrEmpty(text) ? text : text.Replace(longRoot, shortRoot, StringComparison.OrdinalIgnoreCase);
}

/// <summary>
/// The data folder's path has letters Java can't read on this PC and the drive keeps no short (8.3) names: Play
/// stops before Java starts, with this sentence instead of a crash and a Repair that can't help.
/// </summary>
public sealed class JavaPathUnreadableException : Exception
{
    public const string Line =
        "Minecraft can't start: your Windows user name has letters Java can't read on this PC's language setting. " +
        "Turn on \"Beta: Use Unicode UTF-8\" in Windows' Region settings (Administrative, Change system locale), restart, and press Play again.";

    public JavaPathUnreadableException(string root) : base(Line)
    {
        Root = root;
    }

    public string Root { get; }
}
