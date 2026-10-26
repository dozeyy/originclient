using System.Text.Json;
using System.Text.RegularExpressions;
using OriginLauncher.App.Core.Launch;
using Xunit;
using Xunit.Abstractions;

namespace OriginLauncher.Tests;

// The crash corpus: one folder per real-shaped stop (CrashCorpus/<case>/), each with the files a stopped game leaves
// (launch.log, latest.log, crash-*.txt, hs_err_pid*.log), exit.txt (exit code + how it stopped), mods.txt (the
// launch set) and expected.json (what the diagnosis must say). The engine runs on the evidence exactly as the
// launcher builds it, minus the file reading. CI's crash-plant job adds folders from REAL broken launches through
// ORIGIN_CRASH_CORPUS_EXTRA (path list) and collects every result through ORIGIN_CRASH_CORPUS_RESULTS (a JSON file).
public sealed class CrashCorpusTests(ITestOutputHelper output)
{
    private static string CommittedRoot => Path.Combine(AppContext.BaseDirectory, "CrashCorpus");

    private static IEnumerable<string> CaseDirs(bool includeExtra)
    {
        var dirs = Directory.Exists(CommittedRoot) ? Directory.GetDirectories(CommittedRoot).ToList() : [];
        if (includeExtra)
            foreach (var root in (Environment.GetEnvironmentVariable("ORIGIN_CRASH_CORPUS_EXTRA") ?? "")
                         .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                if (Directory.Exists(root))
                    dirs.AddRange(File.Exists(Path.Combine(root, "expected.json")) ? [root] : Directory.GetDirectories(root));
        return dirs.Where(d => File.Exists(Path.Combine(d, "expected.json"))).OrderBy(d => d, StringComparer.Ordinal);
    }

    public static TheoryData<string> Cases()
    {
        var data = new TheoryData<string>();
        foreach (var d in CaseDirs(includeExtra: true)) data.Add(d);
        return data;
    }

    private sealed record Expected(bool Crash, string? Rule, string? Cause, string? Confidence, string[] Culprits, string Fix, string[]? FixJars);

    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };

    internal static CrashEvidence LoadEvidence(string dir)
    {
        var kv = File.ReadAllLines(Path.Combine(dir, "exit.txt"))
            .Select(l => l.Split('=', 2)).Where(p => p.Length == 2)
            .ToDictionary(p => p[0].Trim(), p => p[1].Trim(), StringComparer.OrdinalIgnoreCase);
        string? Get(string k) => kv.TryGetValue(k, out var v) ? v : null;
        static int ParseExit(string? s) => s is null ? 1
            : s.StartsWith("0x", StringComparison.OrdinalIgnoreCase)
                ? unchecked((int)Convert.ToUInt32(s[2..], 16))
                : int.Parse(s, System.Globalization.CultureInfo.InvariantCulture);

        var mods = new List<CrashModEntry>();
        var modsFile = Path.Combine(dir, "mods.txt");
        if (File.Exists(modsFile))
            foreach (var line in File.ReadAllLines(modsFile))
            {
                var p = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (p.Length < 3) continue;
                mods.Add(new CrashModEntry(p[0] == "-" ? "" : p[0], p[1],
                    p[2].Equals("external", StringComparison.OrdinalIgnoreCase),
                    p.Length > 3 ? p[3].Split(',', StringSplitOptions.RemoveEmptyEntries) : null));
            }

        string? Read(string pattern) =>
            Directory.GetFiles(dir, pattern).OrderBy(f => f, StringComparer.Ordinal).LastOrDefault() is { } f
                ? File.ReadAllText(f) : null;

        return new CrashEvidence(
            ParseExit(Get("exit")),
            bool.TryParse(Get("stoppedByPlayer"), out var stopped) && stopped,
            !bool.TryParse(Get("whileStarting"), out var starting) || starting,
            Read("launch.log"), Read("latest.log"), Read("crash-*.txt"), Read("hs_err_pid*.log"),
            mods,
            int.TryParse(Get("ramMb"), out var ram) ? ram : 4096,
            int.TryParse(Get("totalMb"), out var total) ? total : 16384);
    }

    private static (Expected Want, CrashVerdict Got) Run(string dir)
    {
        var want = JsonSerializer.Deserialize<Expected>(File.ReadAllText(Path.Combine(dir, "expected.json")), Json)!;
        return (want, CrashEngine.Diagnose(LoadEvidence(dir)));
    }

    /// <summary>Every mismatch between the expectation and the verdict, empty when the case passes.</summary>
    private static List<string> Mismatches(Expected want, CrashVerdict got)
    {
        var bad = new List<string>();
        if (want.Crash != got.IsCrash) bad.Add($"crash: want {want.Crash}, got {got.IsCrash} ({got.CloseReason})");
        if (!want.Crash || !got.IsCrash) return bad;
        var f = got.Finding;
        if (want.Rule is { } rule && rule != f.Rule) bad.Add($"rule: want {rule}, got {f.Rule}");
        if (want.Cause is { } cause && cause != f.Cause.ToString()) bad.Add($"cause: want {cause}, got {f.Cause}");
        if (want.Confidence is { } conf && conf != f.Confidence.Word()) bad.Add($"confidence: want {conf}, got {f.Confidence.Word()}");
        var culprits = f.Culprits.Select(c => c.ModId).ToArray();
        if (!want.Culprits.SequenceEqual(culprits)) bad.Add($"culprits: want [{string.Join(",", want.Culprits)}], got [{string.Join(",", culprits)}]");
        var fix = got.SafeFix?.Kind.ToString() ?? "None";
        if (want.Fix != fix) bad.Add($"fix: want {want.Fix}, got {fix}");
        if (want.FixJars is { } jars && got.SafeFix is { } sf && !jars.SequenceEqual(sf.Jars))
            bad.Add($"fixJars: want [{string.Join(",", jars)}], got [{string.Join(",", sf.Jars)}]");
        return bad;
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public void Corpus_case_gets_its_expected_diagnosis(string dir)
    {
        var (want, got) = Run(dir);
        var bad = Mismatches(want, got);
        Assert.True(bad.Count == 0, Path.GetFileName(dir) + ": " + string.Join("; ", bad));
        // The fix button only ever exists at strong or confirmed (criterion: auto-fix only when safe).
        if (got.SafeFix is not null) Assert.True(got.Finding.Confidence >= CrashConfidence.Strong);
    }

    /// <summary>Prints one line per case and the unknown rate (crashes the engine could not explain), and writes
    /// the per-case results for CI when ORIGIN_CRASH_CORPUS_RESULTS is set. Asserts only that it ran.</summary>
    [Fact]
    public void Corpus_unknown_rate_is_reported()
    {
        var rows = new List<object>();
        int crashes = 0, unknown = 0;
        foreach (var dir in CaseDirs(includeExtra: true))
        {
            var (want, got) = Run(dir);
            var bad = Mismatches(want, got);
            if (got.IsCrash)
            {
                crashes++;
                if (got.Finding.Confidence == CrashConfidence.Unknown) unknown++;
            }
            var line = $"{(bad.Count == 0 ? "PASS" : "FAIL")} {Path.GetFileName(dir)}: " +
                       (got.IsCrash ? $"crash, {got.Finding.Rule}, {got.Finding.Confidence.Word()}, fix {got.SafeFix?.Kind.ToString() ?? "None"}"
                                    : $"not a crash ({got.CloseReason})") +
                       (bad.Count == 0 ? "" : " | " + string.Join("; ", bad));
            output.WriteLine(line);
            rows.Add(new
            {
                @case = Path.GetFileName(dir), pass = bad.Count == 0, crash = got.IsCrash, rule = got.Finding.Rule,
                confidence = got.Finding.Confidence.Word(), fix = got.SafeFix?.Kind.ToString() ?? "None",
                culprits = got.Finding.Culprits.Select(c => c.ModId).ToArray(), reason = got.CloseReason, mismatches = bad,
            });
        }
        var rate = crashes == 0 ? 0 : 100.0 * unknown / crashes;
        output.WriteLine($"Unknown rate: {unknown}/{crashes} crashes ({rate:0.#}%)");
        if (Environment.GetEnvironmentVariable("ORIGIN_CRASH_CORPUS_RESULTS") is { Length: > 0 } path)
            File.WriteAllText(path, JsonSerializer.Serialize(new { unknown, crashes, rate, cases = rows },
                new JsonSerializerOptions { WriteIndented = true }));
        Assert.NotEmpty(rows);
    }

    [Fact]
    public void Corpus_covers_every_confidence_level_and_normal_closes()
    {
        var got = CaseDirs(includeExtra: false).Select(Run).ToList();
        foreach (var level in Enum.GetValues<CrashConfidence>())
            Assert.Contains(got, g => g.Got.IsCrash && g.Got.Finding.Confidence == level);
        Assert.Contains(got, g => !g.Got.IsCrash);
        Assert.True(got.Count >= 25, $"corpus shrank to {got.Count} cases");
    }

    // ---- Privacy: nothing personal may ever be committed as a fixture ----

    private static readonly Regex[] Leaks =
    [
        new(@"[A-Za-z]:[\\/]+Users[\\/]+(?!<user>)[^\\/\s""'<>|:*?]+", RegexOptions.IgnoreCase),
        new(@"/(?:Users|home)/(?!<user>)[^/\s""'<>|:*?]+", RegexOptions.IgnoreCase),
        new(@"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"),
        new(@"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"),
        new(@"--(?:accessToken|uuid|xuid|username|clientId)[ =]+(?!<redacted>)\S+", RegexOptions.IgnoreCase),
        new(@"Setting user:\s*(?!<player>)\S+", RegexOptions.IgnoreCase),
    ];

    /// <summary>The committed fixtures: every corpus file plus the C# fixture strings.</summary>
    public static TheoryData<string> FixtureFiles()
    {
        var data = new TheoryData<string>();
        if (Directory.Exists(CommittedRoot))
            foreach (var f in Directory.GetFiles(CommittedRoot, "*", SearchOption.AllDirectories).Order(StringComparer.Ordinal))
                data.Add(f);
        // The C# fixture strings, read from the source tree (the compiler records where this file lives).
        var fixtures = Path.Combine(Path.GetDirectoryName(SourceFile())!, "CrashFixtures.cs");
        if (File.Exists(fixtures)) data.Add(fixtures);
        return data;
    }

    private static string SourceFile([System.Runtime.CompilerServices.CallerFilePath] string path = "") => path;

    [Theory]
    [MemberData(nameof(FixtureFiles))]
    public void Fixture_holds_no_user_names_tokens_emails_or_private_paths(string file)
    {
        var text = File.ReadAllText(file);
        foreach (var rx in Leaks)
            Assert.False(rx.IsMatch(text), $"{Path.GetFileName(file)}: private data matching {rx}: {rx.Match(text).Value}");
        // The machine's own user name, so a log pasted from this PC fails here before it is committed. Exact case and
        // whole word: a user called "Will" must not trip on "Program will exit".
        var user = Environment.UserName;
        if (user.Length >= 3)
            Assert.False(Regex.IsMatch(text, $@"(?<![A-Za-z0-9_]){Regex.Escape(user)}(?![A-Za-z0-9_])"),
                $"{Path.GetFileName(file)}: contains this PC's user name");
        // Whatever Copy report would strip must not be there to begin with (the scrub leaves a clean fixture unchanged).
        var scrubbed = CrashReportBundle.Scrub(text);
        if (scrubbed != text)
        {
            var at = Enumerable.Range(0, Math.Min(text.Length, scrubbed.Length)).FirstOrDefault(i => text[i] != scrubbed[i]);
            Assert.Fail($"{Path.GetFileName(file)}: the scrub changes it near \"{text.Substring(Math.Max(0, at - 20), Math.Min(60, text.Length - Math.Max(0, at - 20)))}\"");
        }
    }

    [Fact]
    public void Privacy_check_catches_planted_personal_data()
    {
        // The detector itself must be able to fail (a check that can't fail is worse than none).
        string[] planted =
        [
            @"C:\Users\steve\AppData\Roaming\.minecraft",
            "/home/steve/.minecraft/logs",
            "contact steve@example.com",
            "--accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abc",
            "[Render thread/INFO]: Setting user: Steve",
        ];
        foreach (var p in planted)
            Assert.True(Leaks.Any(rx => rx.IsMatch(p)) || CrashReportBundle.Scrub(p) != p, p);
    }
}
