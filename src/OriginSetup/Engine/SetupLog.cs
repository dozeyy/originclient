namespace OriginSetup.Engine;

// Plain-text log of the last setup/uninstall run, for "Copy details" on the
// error screen and for CI's smoke test: %TEMP%\OriginSetup.log.
public static class SetupLog
{
    private static readonly object Gate = new object();
    public static string FilePath { get; } = Path.Combine(Path.GetTempPath(), "OriginSetup.log");

    public static void Start(string what)
    {
        lock (Gate)
        {
            try { File.WriteAllText(FilePath, $"{DateTime.Now:u} {what}{Environment.NewLine}"); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public static void Write(string line)
    {
        lock (Gate)
        {
            try { File.AppendAllText(FilePath, $"{DateTime.Now:HH:mm:ss} {line}{Environment.NewLine}"); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public static string Read()
    {
        try { return File.ReadAllText(FilePath); }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException) { return ""; }
    }
}
