using System.ComponentModel;
using System.Diagnostics;

namespace OriginLauncher.App.Core.Promos;

/// <summary>Opens banner/ad links in the player's default browser — never inside the launcher.</summary>
public static class ExternalLink
{
    /// <summary>Only web links leave the launcher; file:, ms-settings: and friends never do.</summary>
    public static bool IsOpenable(Uri? url) =>
        url is { IsAbsoluteUri: true } && (url.Scheme == Uri.UriSchemeHttps || url.Scheme == Uri.UriSchemeHttp);

    public static void Open(Uri? url)
    {
        if (!IsOpenable(url)) return;
        try
        {
            Process.Start(new ProcessStartInfo(url!.AbsoluteUri) { UseShellExecute = true });
        }
        catch (Exception ex) when (ex is Win32Exception or InvalidOperationException)
        {
            // No default browser registered — nothing sensible to do.
        }
    }
}
