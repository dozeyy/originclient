using Avalonia;
using Avalonia.Controls;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// The one theme-key lookup for code. A control that is not in a window yet (built in a constructor, before the page is
/// shown) can't reach the app's resources through its own lookup, so this falls back to them; a miss is null, never
/// Avalonia's UnsetValue.
/// </summary>
internal static class ThemeLookup
{
    internal static T? ThemeValue<T>(this IResourceHost host, string key) where T : class =>
        host.TryFindResource(key, out var v) && v is T t ? t
        : Application.Current?.TryGetResource(key, null, out var a) == true ? a as T : null;

    internal static double ThemeDouble(this IResourceHost host, string key, double fallback = 0) =>
        host.TryFindResource(key, out var v) && v is double d ? d
        : Application.Current?.TryGetResource(key, null, out var a) == true && a is double e ? e : fallback;
}
