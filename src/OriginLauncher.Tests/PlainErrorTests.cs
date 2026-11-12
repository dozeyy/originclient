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
    public void A_plain_io_error_without_a_known_code_falls_back_to_the_generic_line()
    {
        Assert.Equal("something unexpected went wrong - try again", PlainError.Reason(new IOException(UserPath)));
    }
}
