using CmlLib.Core.Auth;

namespace OriginLauncher.App.Core.Auth;

// The MSA refresh token travels separately from the MSession — CmlLib.Core's
// MSession only models what's needed to launch the game, not what's needed
// to silently re-authenticate next time. The account store persists this
// refresh token (encrypted) so future launches don't need the browser again.
public sealed class AuthResult
{
    /// <summary>
    /// The Minecraft session. Null only after the interactive Microsoft window
    /// when the Minecraft step was rate-limited (see <see cref="Deferred"/>):
    /// the account is kept and its Minecraft step finished later.
    /// </summary>
    public MSession? Session { get; init; }

    public required string MsaRefreshToken { get; init; }

    /// <summary>When the Minecraft access token stops working (login_with_xbox's expires_in).</summary>
    public DateTimeOffset ExpiresAt { get; init; }

    /// <summary>The 429 that stopped the Minecraft step, when <see cref="Session"/> is null.</summary>
    public MicrosoftAuthException? Deferred { get; init; }
}
