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

    /// <summary>The name a sentence uses (a pending account may not have one yet).</summary>
    public string DisplayName => string.IsNullOrWhiteSpace(Gamertag) ? "Microsoft account" : Gamertag;

    /// <summary>What a screen reader says for the row's choose button and its trash button.</summary>
    public string ChooseLabel => IsPending ? $"{DisplayName}, finish signing in"
        : IsSelected ? $"{DisplayName}, active account" : $"Switch to {DisplayName}";
    public string RemoveLabel => $"Remove {DisplayName}";
}
