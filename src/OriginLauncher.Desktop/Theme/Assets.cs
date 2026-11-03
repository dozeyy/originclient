using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform;

namespace OriginLauncher.Desktop.Theme;

// Bundled files (linked from the WPF launcher by OriginLauncher.Desktop.props): Inter, the art, and the
// two brand files. The logo is only ever origin_logo.png / wordmark.png shown as images (DECISIONS.md,
// 2026-10-25): never redrawn.
public static class Assets
{
    private static readonly string AssemblyName = typeof(Assets).Assembly.GetName().Name!;

    public static Uri Uri(string path) => new($"avares://{AssemblyName}/{path}");

    /// <summary>Inter, the only UI face (section 3), from the bundled static TTFs 400-800.</summary>
    public static FontFamily Inter { get; } = new(Uri("Assets/Fonts"), "#Inter");

    /// <summary>Technical text (crash details, paths).</summary>
    public static FontFamily Mono { get; } = new("DejaVu Sans Mono, Liberation Mono, Menlo, Consolas, monospace");

    private static readonly Dictionary<string, Bitmap?> Cache = new(StringComparer.Ordinal);

    /// <summary>A bundled bitmap, or null when the file isn't bundled (callers keep the space empty).</summary>
    public static Bitmap? Bitmap(string path)
    {
        lock (Cache)
        {
            if (Cache.TryGetValue(path, out var b)) return b;
            try
            {
                var uri = Uri(path);
                b = AssetLoader.Exists(uri) ? new Bitmap(AssetLoader.Open(uri)) : null;
            }
            catch (Exception)
            {
                b = null;
            }
            Cache[path] = b;
            return b;
        }
    }

    /// <summary>A bundled bitmap decoded to a width (art tiers); not cached here.</summary>
    public static Bitmap? BitmapAtWidth(string path, int width)
    {
        try
        {
            var uri = Uri(path);
            if (!AssetLoader.Exists(uri)) return null;
            using var s = AssetLoader.Open(uri);
            return Avalonia.Media.Imaging.Bitmap.DecodeToWidth(s, width, BitmapInterpolationMode.HighQuality);
        }
        catch (Exception)
        {
            return null;
        }
    }

    public static Bitmap? Logo => Bitmap("Assets/Brand/origin_logo.png");
    public static Bitmap? Wordmark => Bitmap("Assets/Brand/wordmark.png");

    /// <summary>Art key for a version family: "26", "1.21", ... (FamilyTheme.GroupOf(v).Key).</summary>
    public static Bitmap? Thumb(string familyKey) => Bitmap($"Assets/Art/{familyKey}-tc.jpg");
}
