using System.Diagnostics;
using System.IO;

namespace OriginLauncher.App.Core;

// Reduces the shader-compile stutter you get with Iris shaderpacks by tuning
// the GPU driver's on-disk shader cache for the game process. Compiled shader
// programs are cached to disk and kept between sessions, so a pack that was
// already compiled once loads without recompiling — no visual change, pure
// smoothness. Applied at launch, gated per vendor (Settings -> Performance).
//
// We deliberately DON'T set __GL_THREADED_OPTIMIZATIONS: it can raise FPS but
// also destabilises Minecraft on some setups, and this feature's whole promise
// is "no downside." Unknown env vars are ignored by other drivers, so the
// vendor toggles never hurt a machine they don't apply to.
public static class ShaderCache
{
    // 4 GiB ceiling — comfortably holds several shaderpacks' compiled programs
    // without letting the cache grow unbounded.
    private const long CacheSizeBytes = 4L * 1024 * 1024 * 1024;

    // Applied to the built game process before it starts. Requires
    // UseShellExecute == false at Start() time (it is — see HomePage's
    // StartWithLifecycleCapture), which is guaranteed for env-var passing.
    public static void Apply(ProcessStartInfo startInfo, bool nvidia, bool amd)
    {
        if (!nvidia && !amd)
        {
            return;
        }

        string? dir = OriginPaths.ShaderCache;
        try
        {
            Directory.CreateDirectory(dir);
        }
        catch
        {
            // If our own directory can't be created, still enable the caches at
            // the drivers' default locations rather than giving up entirely.
            dir = null;
        }

        var env = startInfo.Environment;

        if (nvidia)
        {
            env["__GL_SHADER_DISK_CACHE"] = "1";
            // The key line: NVIDIA otherwise prunes the cache to a small default,
            // which is exactly why large shaderpacks recompile and stutter again
            // on a later launch. Skipping cleanup keeps them cached.
            env["__GL_SHADER_DISK_CACHE_SKIP_CLEANUP"] = "1";
            env["__GL_SHADER_DISK_CACHE_SIZE"] = CacheSizeBytes.ToString();
            if (dir != null)
            {
                env["__GL_SHADER_DISK_CACHE_PATH"] = dir;
            }
        }

        if (amd)
        {
            // Mesa / RadeonSI shader-cache controls (Linux + Proton). On Windows
            // AMD's OpenGL driver manages its own cache with no env-var lever, so
            // these are a harmless no-op there; on Linux/Proton they persist and
            // size the cache the same way the NVIDIA vars do above.
            env["MESA_SHADER_CACHE_DISABLE"] = "false";
            env["MESA_SHADER_CACHE_MAX_SIZE"] = "4G";
            if (dir != null)
            {
                env["MESA_SHADER_CACHE_DIR"] = dir;
            }
        }
    }
}
