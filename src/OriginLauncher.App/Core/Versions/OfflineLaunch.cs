using System.Net.Http;
using System.Net.Sockets;

namespace OriginLauncher.App.Core.Versions;

/// <summary>
/// A launch that needed the internet and couldn't reach it: something this
/// version needs (a library, the loader, an asset) isn't on disk yet. Home shows
/// <see cref="Exception.Message"/> as-is, so it is a plain sentence for the player.
/// The real network error stays in InnerException for the launcher_error log.
/// </summary>
public sealed class OfflineInstallException : Exception
{
    public OfflineInstallException(string version, Exception inner)
        : base($"You're offline and Minecraft {version} isn't fully downloaded yet. Connect to the internet and press Play once to finish the download.", inner)
    {
    }
}

public static class OfflineLaunch
{
    /// <summary>
    /// True when <paramref name="e"/> (or anything it wraps) is "no connection":
    /// refused, unreachable, DNS, a proxy that won't connect, or HttpClient's own
    /// timeout. A server that answered (any HTTP status) is not offline, and the
    /// caller's own cancel never counts.
    /// </summary>
    public static bool IsNoConnection(Exception e, CancellationToken ct)
    {
        if (ct.IsCancellationRequested) return false;
        var stack = new Stack<Exception>();
        stack.Push(e);
        while (stack.Count > 0)
        {
            var x = stack.Pop();
            switch (x)
            {
                case HttpRequestException http when http.StatusCode == null:
                case SocketException:
                case TaskCanceledException when x.InnerException is TimeoutException:
                    return true;
                case HttpRequestException:
                    continue; // the server answered: not this branch
                case AggregateException agg:
                    foreach (var inner in agg.InnerExceptions) stack.Push(inner);
                    continue;
            }
            if (x.InnerException != null) stack.Push(x.InnerException);
        }
        return false;
    }
}
