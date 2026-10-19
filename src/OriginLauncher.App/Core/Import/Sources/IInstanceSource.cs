using System.IO;

namespace OriginLauncher.App.Core.Import.Sources;

// One detector per source launcher (D4). A detector finds instances under the
// root the glue handed it and adds them to the scan. Two rules every detector
// keeps, so a scan can never fail or hang the import panel:
//  * a problem with ONE instance (garbage or truncated metadata) never escapes:
//    the instance is marked "Couldn't read details" or left out;
//  * a missing root is simply "nothing here" — no exception, no note.
// All file reads go through the SourceFs guard (credentials are never opened).
public interface IInstanceSource
{
    SourceKind Kind { get; }

    /// <summary>Adds what this launcher has to <paramref name="into"/>. Never throws for bad data; honours cancellation.</summary>
    Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct);
}

/// <summary>Helpers the detectors share.</summary>
public static class SourceHelpers
{
    /// <summary>A path made absolute and tidy, or null when it is not a usable path.</summary>
    public static string? Normalize(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;
        try
        {
            return Path.TrimEndingDirectorySeparator(Path.GetFullPath(path.Trim()));
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return null;
        }
    }

    /// <summary>
    /// Reads only the <paramref name="wanted"/> keys of an INI-style file (QSettings: with or without a
    /// [General] header, values possibly quoted). Every other value is dropped as it is read — launcher
    /// configs can hold API keys, and nothing here keeps them. Null when the file can't be read.
    /// </summary>
    public static Dictionary<string, string>? ReadIni(string path, SourceFs fs, ISet<string> wanted)
    {
        using var stream = fs.OpenRead(path);
        if (stream == null) return null;
        try
        {
            var values = new Dictionary<string, string>(StringComparer.Ordinal);
            using var reader = new StreamReader(stream, System.Text.Encoding.UTF8, true);
            while (reader.ReadLine() is { } line)
            {
                var text = line.Trim();
                if (text.Length == 0 || text[0] is '[' or ';' or '#') continue;
                var equals = text.IndexOf('=');
                if (equals <= 0) continue;
                var key = text[..equals].Trim();
                if (!wanted.Contains(key)) continue;
                var value = text[(equals + 1)..].Trim();
                if (value.Length >= 2 && value[0] == '"' && value[^1] == '"') value = value[1..^1];
                values[key] = value;
            }
            return values;
        }
        catch (IOException)
        {
            return null;
        }
    }

    /// <summary>Marks an instance as unreadable (keeps what is known).</summary>
    public static DetectedInstance Unreadable(DetectedInstance instance) =>
        instance with { Problem = DetectedInstance.CouldntRead };
}
