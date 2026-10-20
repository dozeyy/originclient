using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The Copy report text: trimmed, and free of tokens, emails and user names.</summary>
public class CrashReportBundleTests
{
    private static readonly CrashDiagnosis Diagnosis = new(CrashCause.OutOfMemory, "OUT OF MEMORY",
        "Minecraft ran out of memory", "x", CrashAction.RaiseMemory, "Raise memory and relaunch");

    [Theory]
    [InlineData("--accessToken eyJhbGciOiJIUzI1NiJ9.abcdefghijkl.zyxwvutsrq", "eyJhbGci")]
    [InlineData("Authorization: Bearer abc123.def456-ghi789", "abc123")]
    [InlineData("\"access_token\": \"SECRETVALUE123\"", "SECRETVALUE123")]
    [InlineData("(Session ID is token:eyJhbGciOiJIUzI1NiJ9.payloadpayload.sigsigsig:00000000)", "payloadpayload")]
    [InlineData("--uuid 069a79f444e94726a5befca90e38aaf5 --xuid 2535", "069a79f4")]
    [InlineData("contact me at someone.name+mc@example.co.uk please", "someone.name")]
    [InlineData("refresh_token=M.R3_BAY.-CVL", "M.R3_BAY")]
    public void Scrub_removes_secrets(string input, string mustNotSurvive) =>
        Assert.DoesNotContain(mustNotSurvive, CrashReportBundle.Scrub(input));

    [Fact]
    public void Scrub_removes_usernames_in_paths_but_keeps_the_rest_of_the_path()
    {
        var s = CrashReportBundle.Scrub(@"at C:\Users\WilliamHenry\AppData\Local\OriginLauncher\logs\x.log and /home/bob/.minecraft");
        Assert.DoesNotContain("WilliamHenry", s);
        Assert.DoesNotContain("bob", s);
        Assert.Contains(@"C:\Users\<user>\AppData\Local\OriginLauncher\logs\x.log", s);
    }

    [Fact]
    public void Scrub_replaces_known_names_whole_word_only()
    {
        var s = CrashReportBundle.Scrub("Setting user: SteveTheGreat\nSteveTheGreat joined; Steve123 stayed; misteveTheGreatx", ["SteveTheGreat"]);
        Assert.DoesNotContain("SteveTheGreat\n", s + "\n");
        Assert.Contains("Steve123", s);
        Assert.Contains("misteveTheGreatx", s);
    }

    [Fact]
    public void Scrub_leaves_ordinary_log_lines_alone()
    {
        const string line = "[12:00:01] [main/INFO]: Loading Minecraft 1.21.1 with Fabric Loader 0.17.3";
        Assert.Equal(line, CrashReportBundle.Scrub(line));
    }

    [Fact]
    public void Bundle_has_the_versions_and_trims_the_log_to_the_last_200_lines()
    {
        var log = string.Join("\n", Enumerable.Range(1, 500).Select(i => $"line-{i}"));
        var text = CrashReportBundle.Build(new CrashReportBundle.Input("1.0.56", "1.21.1", 4096, 16384, 1, Diagnosis,
            log, "Time: now\nJava Version: 21.0.2, Microsoft\nDescription: boom"));

        Assert.Contains("Launcher     : 1.0.56", text);
        Assert.Contains("Minecraft    : 1.21.1", text);
        Assert.Contains("Java         : 21.0.2, Microsoft", text);
        Assert.Contains("4096 MB given of 16384 MB installed", text);
        Assert.Contains("line-500", text);
        Assert.Contains("line-301", text);
        Assert.DoesNotContain("line-300\n", text + "\n");
        Assert.Contains("crash report (start)", text);
    }

    [Fact]
    public void Bundle_scrubs_everything_including_the_crash_report()
    {
        var text = CrashReportBundle.Build(new CrashReportBundle.Input("1", "1.21.1", 4096, 8192, 1, Diagnosis,
            "Setting user: Steve\n--accessToken abc.def.ghi mail me a@b.com",
            @"File: C:\Users\Will Henry\x", ["WinUserName"]));
        Assert.DoesNotContain("a@b.com", text);
        Assert.DoesNotContain("abc.def.ghi", text);
        Assert.DoesNotContain("Steve\n", text);
    }

    [Fact]
    public void Bundle_without_a_log_says_so_and_stays_under_the_size_cap()
    {
        var empty = CrashReportBundle.Build(new CrashReportBundle.Input("1", "1.21.1", 4096, 8192, 1, Diagnosis, null, null));
        Assert.Contains("(no log was captured)", empty);
        Assert.Contains("Java         : unknown", empty);

        var huge = CrashReportBundle.Build(new CrashReportBundle.Input("1", "1.21.1", 4096, 8192, 1, Diagnosis,
            string.Join("\n", Enumerable.Repeat(new string('x', 5000), 300)), null));
        Assert.True(huge.Length <= CrashReportBundle.MaxTotalChars + 20);
    }
}
