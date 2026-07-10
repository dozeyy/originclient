using System.Diagnostics;
using System.IO;

namespace OriginLauncher.App.Core;

// Reduces the shader-compile stutter you get with Iris shaderpacks by tuning
// the GPU driver's on-disk shader cache for the game process. Compiled shader
// programs are cached to disk and kept between sessions, so a pack that was
// already compiled once loads without recompiling — no visual change, pure
// smoothness. Applied at launch, gated on LauncherSettings.ShaderCacheOptimization.
//
// These are NVIDIA's OpenGL shader-cache controls — the biggest, safe lever on
// Windows. Other drivers ignore unknown environment variables, so setting them
// is harmless there. We deliberately DON'T set __GL_THREADED_OPTIMIZATIONS: it
// can raise FPS but also destabilises Minecraft on some setups, and this
// feature's whole promise is "no downside."
public static class ShaderCache
{
    // 4 GiB ceiling — comfortably holds several shaderpacks' compiled programs
    // without letting the cache grow unbounded.
    private const long CacheSizeBytes = 4L * 1024 * 1024 * 1024;

    // Applied to the built game process before it starts. Requires
    // UseShellExecute == false at Start() time (it is — see HomePage's
    // StartWithLifecycleCapture), which is guaranteed for env-var passing.
    public static void Apply(ProcessStartInfo startInfo)
    {
        string? dir = OriginPaths.ShaderCache;
        try
        {
            Directory.CreateDirectory(dir);
        }
        catch
        {
            // If our own directory can't be created, still enable the cache at
            // the driver's default location rather than giving up entirely.
            dir = null;
        }

        var env = startInfo.Environment;
        env["__GL_SHADER_DISK_CACHE"] = "1";
        // The key line: NVIDIA otherwise prunes the cache to a small default,
        // which is exactly why large shaderpacks recompile and stutter again on
        // a later launch. Skipping cleanup keeps them cached.
        env["__GL_SHADER_DISK_CACHE_SKIP_CLEANUP"] = "1";
        env["__GL_SHADER_DISK_CACHE_SIZE"] = CacheSizeBytes.ToString();
        if (dir != null)
        {
            env["__GL_SHADER_DISK_CACHE_PATH"] = dir;
        }
    }
}
