using OriginLauncher.App.Core.Launch;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The Copy report text: trimmed, and free of tokens, emails and user names.</summary>
public class CrashReportBundleTests
{
    private static readonly CrashVerdict Diagnosis = CrashEngine.Diagnose(new CrashEvidence(1, false, false,
        "java.lang.OutOfMemoryError: Java heap space", null, null, null, [], 4096, 16384));

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

    [Theory]
    [InlineData("Connecting to 203.0.113.45, 25565", "203.0.113.45")]
    [InlineData("[Server thread/INFO]: UUID of player Notch is 069a79f4-44e9-4726-a5be-fca90e38aaf5", "069a79f4")]
    [InlineData("[Server thread/INFO]: UUID of player Notch is 069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch")]
    [InlineData("local address 192.168.1.20 joined", "192.168.1.20")]
    // From the privacy review (2026-10-25): server host names, IPv6, bare account ids, whole Authorization values.
    [InlineData("[Render thread/INFO]: Connecting to mc.friendshome.net, 25565", "friendshome")]
    [InlineData("Server IP: play.example-server.org", "example-server")]
    [InlineData("joined [2001:db8:85a3::8a2e:370:7334]:25565", "8a2e")]
    [InlineData(@"--gameDir C:\x\instances\1.21.1\accounts\069a79f4-44e9-4726-a5be-fca90e38aaf5\", "069a79f4")]
    [InlineData("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"x\"}", "069a79f4")]
    [InlineData("Authorization: XBL3.0 x=1234567890;abcdefSHORT", "abcdefSHORT")]
    public void Scrub_removes_addresses_and_player_ids(string input, string mustNotSurvive) =>
        Assert.DoesNotContain(mustNotSurvive, CrashReportBundle.Scrub(input));

    [Fact]
    public void Build_scrubs_before_cutting_long_lines()
    {
        // 284 x's + a space puts the 300-char cut inside the name: "C:\Users\Sam Ri". Cut first, and the
        // with-spaces pattern has no separator to anchor on, so part of the surname leaked.
        var line = new string('x', 284) + @" C:\Users\Sam Rivers\AppData\x.jar";
        var text = CrashReportBundle.Build(new CrashReportBundle.Input("1", "1.21.1", 4096, 8192, 1, Diagnosis,
            line, line));
        Assert.DoesNotContain("Sam", text);
        Assert.DoesNotContain("Ri ...", text); // the old leak: "C:\Users\<user> Ri ..."
    }

    [Fact]
    public void Scrub_hides_the_host_netty_names_before_an_address()
    {
        var s = CrashReportBundle.Scrub("io.netty.channel.AbstractChannel$AnnotatedConnectException: Connection refused: mc.example.net/203.0.113.7:25565");
        Assert.DoesNotContain("mc.example.net", s);
        Assert.DoesNotContain("203.0.113.7", s);
        Assert.Contains("Connection refused: <server>/<ip>:25565", s);
        // Java class names and versions next to no address stay as they are.
        Assert.Contains("io.netty.channel.AbstractChannel", s);
        Assert.Equal("<server>/[<ip>]:25565", CrashReportBundle.Scrub("play.example.org/[2001:db8::1]:25565"));
    }

    [Fact]
    public void Scrub_hides_a_user_name_with_a_space_whole()
    {
        var s = CrashReportBundle.Scrub(@"at C:\Users\Sam Rivers\AppData\Local\OriginLauncher\x.jar and /home/sam rivers/", ["Sam Rivers"]);
        Assert.DoesNotContain("Sam", s);
        Assert.DoesNotContain("Rivers", s);
        // ...and without being told the name, a path segment with a space still goes as one piece.
        Assert.Equal(@"C:\Users\<user>\AppData", CrashReportBundle.Scrub(@"C:\Users\Sam Rivers\AppData"));
    }

    [Theory]
    [InlineData("Forge Mod Loader version 11.15.1.2318 for Minecraft 1.8.9 loading")]
    [InlineData("Starting integrated server on 127.0.0.1:25565")]
    [InlineData("Bound to 0.0.0.0")]
    [InlineData("UCHIJA\tFML{8.0.99.99} [Forge Mod Loader] (forge-1.8.9.jar)")]
    [InlineData("[12:00:01] [Render thread/INFO]: java.util.function.Function::apply at 13:45:07.123")]
    [InlineData("Starting integrated server on [::1]:25565")]
    public void Scrub_keeps_versions_and_local_addresses(string line) =>
        Assert.Equal(line, CrashReportBundle.Scrub(line));

    [Fact]
    public void Bundle_states_the_diagnosis_with_its_confidence_word()
    {
        var text = CrashReportBundle.Build(new CrashReportBundle.Input("1", "1.21.1", 4096, 16384, 1, Diagnosis, "log", null));
        Assert.Contains("[confirmed, rule out-of-memory]", text);
        Assert.Contains("Evidence     : java.lang.OutOfMemoryError: Java heap space", text);
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
