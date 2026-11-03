using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Accounts;

// Linux: no DPAPI, so tokens are sealed with AES-256-GCM, the same way as on a Mac. The key comes
// from LinuxKeyStore.Linux.cs: the desktop's Secret Service keyring (GNOME Keyring, KWallet) when
// it answers, else a 0600 key file in the data folder that only opens on this machine (bound to
// /etc/machine-id). accounts.json only ever holds ciphertext.
// The blob's first byte says which key sealed it (1 = keyring, 2 = key file), so a keyring that
// appears or disappears later never makes the other kind unreadable. A missing, unreadable or
// different key (keyring reset, data copied to another machine, a tampered blob) makes decrypting
// throw CryptographicException, which AccountStore turns into "not signed in": the player signs in
// again, nothing is ever stored or read in plaintext.
// Blob layout: kind byte | 12-byte nonce | 16-byte tag | ciphertext.
public static partial class AccountStore
{
    private const int NonceSize = 12;
    private const int TagSize = 16;

    internal static byte[] PlatformProtect(byte[] plain)
    {
        var (kind, key) = LinuxKeyStore.GetOrCreateSealingKey();
        try
        {
            var blob = new byte[1 + NonceSize + TagSize + plain.Length];
            blob[0] = kind;
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
        if (blob.Length < 1 + NonceSize + TagSize || blob[0] is not (LinuxKeyStore.KeyringKind or LinuxKeyStore.FileKind))
            throw new CryptographicException("Not an Origin account blob.");
        // Never creates a key: no key means nothing sealed here can be opened, so the player signs in again.
        var key = LinuxKeyStore.TryReadKey(blob[0]) ?? throw new CryptographicException("No account key on this machine.");
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
