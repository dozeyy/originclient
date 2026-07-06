using System.IO;

namespace OriginLauncher.App.Core;

public static class OriginPaths
{
    public static string Root { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OriginLauncher");

    public static string Instances => Path.Combine(Root, "instances");
    public static string Accounts => Path.Combine(Root, "accounts");
    public static string Logs => Path.Combine(Root, "logs");

    public static void EnsureScaffold()
    {
        Directory.CreateDirectory(Instances);
        Directory.CreateDirectory(Accounts);
        Directory.CreateDirectory(Logs);
    }
}
