namespace OriginLauncher.App.Core.Models;

public sealed class MinecraftAccount
{
    public required string Id { get; init; }
    public required string Gamertag { get; init; }
    public DateTimeOffset LastUsedUtc { get; init; }
    public bool IsSelected { get; set; }

    /// <summary>Added while Microsoft was rate-limiting: clicking it finishes the sign-in.</summary>
    public bool IsPending { get; init; }

    /// <summary>The row's second line.</summary>
    public string Subtitle => IsPending
        ? "Click to finish signing in"
        : $"Last used {LastUsedUtc.ToLocalTime():MMM d, yyyy}";
}
