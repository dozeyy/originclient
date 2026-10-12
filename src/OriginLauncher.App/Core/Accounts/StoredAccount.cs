namespace OriginLauncher.App.Core.Accounts;

public sealed class StoredAccount
{
    public required string Id { get; set; } // Minecraft profile UUID (a "pending-..." placeholder while PendingMinecraftSignIn)
    public required string Gamertag { get; set; }
    public DateTimeOffset LastUsedUtc { get; set; }
    public bool IsSelected { get; set; }

    // DPAPI-protected (CurrentUser scope), base64-encoded. Device-bound by
    // construction — DPAPI ties it to the Windows user profile it was
    // encrypted under, so the file is useless if copied to another machine.
    public required string ProtectedRefreshToken { get; set; }

    // The last Minecraft session (access token, uuid, name, expiry) as JSON,
    // protected exactly like the refresh token above (AccountStore.ProtectSession).
    // Lets Play and Friends reuse it across launcher restarts until shortly
    // before it expires, instead of signing in again — Mojang rate-limits
    // sign-ins (HTTP 429). Null = none saved; sign in when next needed.
    public string? ProtectedMinecraftSession { get; set; }

    // Added while Microsoft was rate-limiting sign-ins: the Microsoft window
    // was completed (its refresh token is stored) but the Minecraft step
    // isn't, so the profile — and the real Id — isn't known yet. Such an
    // account is never selected and never plays; clicking it in the account
    // switcher finishes its sign-in (MinecraftSessionBroker.FinishPendingAsync).
    public bool PendingMinecraftSignIn { get; set; }
}
