namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Reads a built java argument list the way the JDK's launcher does (java.base launcher, java.c): options start
/// with "-", a few take their value as the NEXT argument, and the first argument that is neither ends the options
/// and is run as the main class. Used by the Mac --launch-plan / --play-plan proof, so CI fails when a stray value (CmlLib's
/// two-part "-Xdock:name" "Minecraft", 2026-11-03) would become the main class, even though the real main class is
/// still somewhere in the line.
/// </summary>
public static class JavaArgv
{
    // The java launcher options whose value is the following argument (java.c IsWhiteSpaceOption).
    private static readonly HashSet<string> TakesValue = new(StringComparer.Ordinal)
    {
        "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path", "--add-modules",
        "--enable-native-access", "--limit-modules", "--add-exports", "--add-opens", "--add-reads", "--patch-module",
    };

    /// <summary>
    /// Null when java will run one of <paramref name="mainClasses"/>; otherwise a sentence naming the argument java
    /// would read as the main class instead. <paramref name="args"/> is everything after the java executable.
    /// </summary>
    public static string? MainClassProblem(IReadOnlyList<string> args, IReadOnlyCollection<string> mainClasses)
    {
        for (var i = 0; i < args.Count; i++)
        {
            var a = args[i];
            if (a.Length > 1 && a[0] == '-')
            {
                // The Mac launcher only reads -Xdock:name=<name> / -Xdock:icon=<path>; without "=" the value is
                // a separate argument and becomes the main class.
                if (a.StartsWith("-Xdock:", StringComparison.Ordinal) && !a.Contains('='))
                    return $"'{a}' has no '=': java reads the next argument ('{(i + 1 < args.Count ? args[i + 1] : "")}') as the main class";
                if (TakesValue.Contains(a)) i++;
                continue;
            }
            return mainClasses.Contains(a)
                ? null
                : $"java would run '{a}' as the main class (argument {i + 1}); expected {string.Join(" or ", mainClasses)}";
        }
        return "no main class: every argument is a JVM option";
    }
}
