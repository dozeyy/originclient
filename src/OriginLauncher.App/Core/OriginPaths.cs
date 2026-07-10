using System.IO;

namespace OriginLauncher.App.Core;

public static class OriginPaths
{
    public static string Root { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OriginLauncher");

    public static string Instances => Path.Combine(Root, "instances");
    public static string Accounts => Path.Combine(Root, "accounts");
    public static string Logs => Path.Combine(Root, "logs");
    public static string OptiFineCache => Path.Combine(Root, "optifine-cache");

    // Persistent home for the GPU driver's compiled-shader cache (see
    // Core/ShaderCache.cs) — kept under our data root so it survives across
    // instances and game versions, maximising cache hits between sessions.
    public static string ShaderCache => Path.Combine(Root, "shader-cache");

    // Read-only asset shipped alongside the exe (see the Content item in
    // OriginLauncher.App.csproj that copies it out of OriginClient.Mod's own
    // Gradle build output) — not user data, so it lives next to the app
    // binary rather than under %LocalAppData%. Resolved from the running
    // process's own directory so this works identically from a dev build and
    // from wherever a setup installer actually places the app.
    public static string BundledOriginClientJar => Path.Combine(
        AppContext.BaseDirectory, "Bundled", "OriginClient", "originclient.jar");

    public static void EnsureScaffold()
    {
        Directory.CreateDirectory(Instances);
        Directory.CreateDirectory(Accounts);
        Directory.CreateDirectory(Logs);
        Directory.CreateDirectory(OptiFineCache);
    }
}
