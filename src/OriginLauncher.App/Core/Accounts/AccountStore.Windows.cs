using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Accounts;

// Windows: DPAPI under the current user. The blob only decrypts for this Windows
// user on this PC (device-bound at rest); another profile gets CryptographicException.
public static partial class AccountStore
{
    private static byte[] PlatformProtect(byte[] bytes) =>
        ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser);

    private static byte[] PlatformUnprotect(byte[] bytes) =>
        ProtectedData.Unprotect(bytes, null, DataProtectionScope.CurrentUser);
}
