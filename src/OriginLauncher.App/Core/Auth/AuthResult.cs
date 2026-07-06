using CmlLib.Core.Auth;

namespace OriginLauncher.App.Core.Auth;

// The MSA refresh token travels separately from the MSession — CmlLib.Core's
// MSession only models what's needed to launch the game, not what's needed
// to silently re-authenticate next time. The account store persists this
// refresh token (encrypted) so future launches don't need the browser again.
public sealed class AuthResult
{
    public required MSession Session { get; init; }
    public required string MsaRefreshToken { get; init; }
}
