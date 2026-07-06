namespace OriginLauncher.App.Core.Models;

public sealed class MinecraftAccount
{
    public required string Id { get; init; }
    public required string Gamertag { get; init; }
    public DateTimeOffset LastUsedUtc { get; init; }
    public bool IsSelected { get; set; }
}
