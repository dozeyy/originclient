using System.Security.Cryptography;
using OriginLauncher.App.Core.Auth;
using Xunit;

namespace OriginLauncher.Tests;

// On a Mac, sealing a token goes through the Keychain, which can be locked or refuse
// (CryptographicException). The sign-in paths must turn that into the plain-sentence failure
// every surface already shows, not an unexplained crash.
public class SealedSignInTests
{
    [Fact]
    public void A_keychain_refusal_becomes_a_readable_sign_in_failure()
    {
        var e = Assert.Throws<MicrosoftAuthException>(() =>
            MicrosoftAuthException.Sealed(() => throw new CryptographicException("Keychain read failed (security exit 51).")));
        Assert.Equal(MicrosoftAuthException.KeychainStage, e.Stage);
        Assert.Equal(MicrosoftAuthException.KeychainMessage, e.Message);
        Assert.Contains("security exit 51", e.Detail);
        Assert.False(e.RateLimited);
    }

    [Fact]
    public void A_working_seal_passes_its_result_through() =>
        Assert.Equal("sealed", MicrosoftAuthException.Sealed(() => "sealed"));
}
