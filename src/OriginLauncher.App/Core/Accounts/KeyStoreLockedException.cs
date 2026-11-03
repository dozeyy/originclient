using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Accounts;

/// <summary>
/// The OS store that holds Origin's account key (the Linux keyring, the Mac Keychain) is locked, its unlock prompt was
/// dismissed, or it didn't answer in time. Kept apart from "no key / wrong key" so Play can say what to do ("unlock it
/// and press Play again") instead of "add this account again": nothing is wrong with the account. A
/// CryptographicException, so every existing "can't unseal = not signed in" catch still holds. Windows (DPAPI) never
/// throws it.
/// </summary>
public sealed class KeyStoreLockedException(string message) : CryptographicException(message);
