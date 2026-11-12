using System.ComponentModel;
using System.IO;
using System.Net.Http;
using OriginLauncher.App.Core;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// PlainError is what every launcher screen shows for a file or network failure: plain words, never .NET's own text
/// (which carries the full path and with it the Windows user name).
/// </summary>
public class PlainErrorTests
{
    private const string UserPath = @"C:\Users\SomePlayer\AppData\Local\OriginLauncher\instances\1.21.1\mods\sodium.jar";

    private static IOException Win32Io(int code) =>
        new($"The process cannot access the file '{UserPath}'.", unchecked((int)0x80070000) | code);

    [Theory]
    [InlineData(112, "the disk is full")]
    [InlineData(39, "the disk is full")]
    [InlineData(32, "in use by another program")]
    [InlineData(33, "in use by another program")]
    public void Windows_io_codes_get_their_own_words(int code, string expected)
    {
        Assert.Contains(expected, PlainError.Reason(Win32Io(code)));
    }

    public static TheoryData<Exception, string> Cases => new()
    {
        { new UnauthorizedAccessException($"Access to the path '{UserPath}' is denied."), "antivirus" },
        { new FileNotFoundException("gone", UserPath), "missing" },
        { new DirectoryNotFoundException($"Could not find a part of the path '{UserPath}'."), "missing" },
        { new PathTooLongException(UserPath), "too long" },
        { new InvalidDataException("End of Central Directory record could not be found."), "damaged" },
        { new HttpRequestException("No such host is known. (api.modrinth.com:443)"), "internet connection" },
        { new HttpRequestException("503", null, System.Net.HttpStatusCode.ServiceUnavailable), "busy" },
        { new HttpRequestException("429", null, System.Net.HttpStatusCode.TooManyRequests), "busy" },
        { new HttpRequestException("404", null, System.Net.HttpStatusCode.NotFound), "error 404" },
        { new TaskCanceledException("The request was canceled due to the configured HttpClient.Timeout of 100 seconds"), "too slow" },
        { new Win32Exception(5, "Access is denied"), "antivirus" },
        { new InvalidOperationException($"Sequence contains no elements at {UserPath}"), "something unexpected" },
    };

    [Theory]
    [MemberData(nameof(Cases))]
    public void Each_kind_reads_as_plain_words(Exception error, string expected)
    {
        Assert.Contains(expected, PlainError.Reason(error));
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public void No_path_or_user_name_reaches_the_screen(Exception error, string _)
    {
        var line = PlainError.Sentence("Couldn't copy the mods", error);
        Assert.DoesNotContain("SomePlayer", line);
        Assert.DoesNotContain(@"C:\", line);
        Assert.DoesNotContain("Exception", line);
    }

    [Fact]
    public void A_wrapped_cause_is_found_inside_the_chain()
    {
        var wrapped = new InvalidOperationException("Install failed", new AggregateException(Win32Io(112)));
        Assert.Equal("Couldn't start Minecraft: the disk is full - free up some space and try again.",
            PlainError.Sentence("Couldn't start Minecraft", wrapped));
    }

    [Fact]
    public void A_server_that_answered_is_not_blamed_on_the_internet_connection()
    {
        var forbidden = new HttpRequestException("403", null, System.Net.HttpStatusCode.Forbidden);
        Assert.DoesNotContain("internet", PlainError.Reason(forbidden));
        Assert.Contains("error 403", PlainError.Reason(forbidden));
    }

    [Fact]
    public void The_full_error_goes_to_the_log_while_the_screen_gets_plain_words()
    {
        var logged = new System.Collections.Concurrent.ConcurrentBag<string>();
        var before = PlainError.Log;
        PlainError.Log = logged.Add;
        try
        {
            NullReferenceException thrown;
            try { throw new NullReferenceException("Object reference not set to an instance of an object."); }
            catch (NullReferenceException ex) { thrown = ex; }

            var line = PlainError.Sentence("Couldn't check that mod", thrown);

            Assert.Equal("Couldn't check that mod: something unexpected went wrong - try again.", line);
            // Other tests may call Sentence meanwhile (the sink is static): find this one's entry.
            var entry = Assert.Single(logged, e => e.StartsWith(line, StringComparison.Ordinal) && e.Contains("NullReferenceException"));
            Assert.Contains(nameof(The_full_error_goes_to_the_log_while_the_screen_gets_plain_words), entry); // the stack
        }
        finally
        {
            PlainError.Log = before;
        }
    }

    [Fact]
    public void A_log_that_throws_never_reaches_the_caller()
    {
        var before = PlainError.Log;
        PlainError.Log = _ => throw new IOException("log locked");
        try
        {
            Assert.Equal("Couldn't save the change: the disk is full - free up some space and try again.",
                PlainError.Sentence("Couldn't save the change", Win32Io(112)));
        }
        finally
        {
            PlainError.Log = before;
        }
    }

    [Fact]
    public void LogTo_writes_one_entry_per_error_into_errors_log()
    {
        var dir = Path.Combine(Path.GetTempPath(), "origin-plainerror-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        var before = PlainError.Log;
        try
        {
            PlainError.LogTo(dir);
            PlainError.Sentence("Couldn't copy the mods", Win32Io(32));
            var text = File.ReadAllText(Path.Combine(dir, "errors.log"));
            Assert.Contains("Couldn't copy the mods: a file is in use", text);
            Assert.Contains("System.IO.IOException", text);
        }
        finally
        {
            PlainError.Log = before;
            Directory.Delete(dir, recursive: true);
        }
    }

    [Fact]
    public void A_plain_io_error_without_a_known_code_falls_back_to_the_generic_line()
    {
        Assert.Equal("something unexpected went wrong - try again", PlainError.Reason(new IOException(UserPath)));
    }
}
