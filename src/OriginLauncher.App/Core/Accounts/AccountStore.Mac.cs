using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Accounts;

// Mac: there is no DPAPI, so tokens are sealed with AES-256-GCM and the 32-byte key lives only
// in the user's login Keychain (MacKeychain.Mac.cs). accounts.json only ever holds ciphertext.
// A missing, unreadable or different key (Keychain reset, copied to another Mac or user, a
// tampered blob) makes decrypting throw CryptographicException, which AccountStore turns into
// "not signed in": the player signs in again, nothing is ever stored or read in plaintext.
// Blob layout: version byte (1) | 12-byte nonce | 16-byte tag | ciphertext.
public static partial class AccountStore
{
    private const byte BlobVersion = 1;
    private const int NonceSize = 12;
    private const int TagSize = 16;

    internal static byte[] PlatformProtect(byte[] plain)
    {
        var key = MacKeychain.GetOrCreateKey();
        try
        {
            var blob = new byte[1 + NonceSize + TagSize + plain.Length];
            blob[0] = BlobVersion;
            var nonce = blob.AsSpan(1, NonceSize);
            RandomNumberGenerator.Fill(nonce); // a fresh random nonce per blob: never reused under one key
            using var aes = new AesGcm(key, TagSize);
            aes.Encrypt(nonce, plain, blob.AsSpan(1 + NonceSize + TagSize), blob.AsSpan(1 + NonceSize, TagSize));
            return blob;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
        }
    }

    internal static byte[] PlatformUnprotect(byte[] blob)
    {
        if (blob.Length < 1 + NonceSize + TagSize || blob[0] != BlobVersion)
            throw new CryptographicException("Not an Origin account blob.");
        // Never creates a key: no key means nothing sealed here can be opened, so the player signs in again.
        var key = MacKeychain.TryReadKey() ?? throw new CryptographicException("No account key in the Keychain.");
        try
        {
            var plain = new byte[blob.Length - 1 - NonceSize - TagSize];
            using var aes = new AesGcm(key, TagSize);
            // A wrong key or any changed byte fails the tag check (AuthenticationTagMismatchException is a CryptographicException).
            aes.Decrypt(blob.AsSpan(1, NonceSize), blob.AsSpan(1 + NonceSize + TagSize), blob.AsSpan(1 + NonceSize, TagSize), plain);
            return plain;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
        }
    }
}
