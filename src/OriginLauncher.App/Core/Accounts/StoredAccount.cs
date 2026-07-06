namespace OriginLauncher.App.Core.Accounts;

public sealed class StoredAccount
{
    public required string Id { get; set; } // Minecraft profile UUID
    public required string Gamertag { get; set; }
    public DateTimeOffset LastUsedUtc { get; set; }
    public bool IsSelected { get; set; }

    // DPAPI-protected (CurrentUser scope), base64-encoded. Device-bound by
    // construction — DPAPI ties it to the Windows user profile it was
    // encrypted under, so the file is useless if copied to another machine.
    public required string ProtectedRefreshToken { get; set; }
}
