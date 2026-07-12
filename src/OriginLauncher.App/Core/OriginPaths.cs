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

    // Read-only assets shipped alongside the exe (see the Content items in
    // OriginLauncher.App.csproj that copy them out of each OriginClient.Mod*
    // Gradle build output) — not user data, so they live next to the app
    // binary rather than under %LocalAppData%. Resolved from the running
    // process's own directory so this works identically from a dev build and
    // from wherever a setup installer actually places the app.
    //
    // One jar per supported Minecraft API family (originclient-1.20.jar,
    // originclient-1.21.1.jar, ...). VersionManager's Origin-build registry
    // maps each offered MC version to the matching filename here.
    public static string BundledOriginClientDir => Path.Combine(
        AppContext.BaseDirectory, "Bundled", "OriginClient");

    public static string BundledOriginClientJar(string jarFileName) =>
        Path.Combine(BundledOriginClientDir, jarFileName);

    public static void EnsureScaffold()
    {
        Directory.CreateDirectory(Instances);
        Directory.CreateDirectory(Accounts);
        Directory.CreateDirectory(Logs);
        Directory.CreateDirectory(OptiFineCache);
    }
}
