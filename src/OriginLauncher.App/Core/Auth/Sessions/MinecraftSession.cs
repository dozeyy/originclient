namespace OriginLauncher.App.Core.Auth.Sessions;

// The pure (no WPF, no CmlLib, no DPAPI) side of Minecraft sign-in caching,
// linked into OriginLauncher.Tests. MinecraftSessionBroker is the app's glue.

/// <summary>
/// A Minecraft session as Origin keeps it: what the game needs, plus when
/// Mojang's access token stops working (login_with_xbox's expires_in,
/// normally 24 h). Persisted only DPAPI-protected (AccountStore).
/// </summary>
public sealed record MinecraftSession(string Name, string Uuid, string AccessToken, string Xuid, DateTimeOffset ExpiresAt)
{
    // Never let the access token reach a log or a debugger tooltip by accident.
    public override string ToString() => $"MinecraftSession {{ Name = {Name}, ExpiresAt = {ExpiresAt:u} }}";
}

/// <summary>What one silent Microsoft -> Minecraft sign-in produced.</summary>
public sealed record SilentSignIn(MinecraftSession Session, string RefreshToken)
{
    public override string ToString() => $"SilentSignIn {{ {Session} }}";
}

/// <summary>Mojang's answer when asked whether a cached token still works.</summary>
public enum TokenCheck
{
    /// <summary>Mojang accepted it.</summary>
    Valid,
    /// <summary>Mojang refused it (401): sign in again.</summary>
    Rejected,
    /// <summary>No clear answer (offline, 5xx, 429): use the cached token anyway.</summary>
    Unknown,
}

/// <summary>Where an account's secrets live (the app: accounts.json, DPAPI-protected).</summary>
public interface ISessionVault
{
    /// <summary>The account's Microsoft refresh token; null = unreadable or no such account (re-add it).</summary>
    string? RefreshToken(string accountId);

    /// <summary>The account's saved Minecraft session, if one was saved and can be read.</summary>
    MinecraftSession? SavedSession(string accountId);

    /// <summary>
    /// Persist a sign-in at once: Microsoft rotated the refresh token, so the
    /// old one is already dead. A pending account (added while Microsoft was
    /// rate-limiting) becomes the real account here: its id turns into the
    /// Minecraft UUID.
    /// </summary>
    void Save(string accountId, SilentSignIn signIn);

    /// <summary>Drop the saved Minecraft session (Mojang refused it).</summary>
    void Forget(string accountId);

    /// <summary>Bump "last used" (only playing does).</summary>
    void MarkUsed(string accountId);
}

/// <summary>The network side: the full silent chain, and Mojang's token check.</summary>
public interface ISessionSignIn
{
    /// <summary>MSA refresh -> Xbox -> XSTS -> login_with_xbox -> profile. Throws MicrosoftAuthException.</summary>
    Task<SilentSignIn> SignInAsync(string refreshToken, CancellationToken ct);

    /// <summary>One cheap profile call with the cached token. Never throws.</summary>
    Task<TokenCheck> CheckAsync(string accessToken, CancellationToken ct);
}
