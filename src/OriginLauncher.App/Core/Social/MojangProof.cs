using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Social;

/// <summary>
/// The player's Mojang certificate (POST /player/certificates): a key pair
/// Mojang signed for this account. The private key lives in this object in
/// memory only — never on disk, never in a log (ToString hides it).
/// </summary>
public sealed class PlayerCertificate
{
    /// <summary>PKCS#8 DER of the private key. Only <see cref="MojangProof.Sign"/> reads it.</summary>
    internal byte[] PrivateKeyPkcs8 { get; }

    /// <summary>X.509 SubjectPublicKeyInfo DER — the exact bytes Mojang's publicKeySignatureV2 covers.</summary>
    public byte[] PublicKeySpki { get; }

    /// <summary>Mojang's base64 signature over uuid ‖ expiresAt ‖ public key (checked by the service).</summary>
    public string PublicKeySignatureV2 { get; }

    /// <summary>The certificate's expiry in ms since epoch, truncated like Java's Instant.toEpochMilli().</summary>
    public long ExpiresAtMs { get; }

    /// <summary>When Mojang wants a new certificate fetched (ms); falls back to the expiry.</summary>
    public long RefreshedAfterMs { get; }

    internal PlayerCertificate(byte[] privateKeyPkcs8, byte[] publicKeySpki, string publicKeySignatureV2,
        long expiresAtMs, long refreshedAfterMs)
    {
        PrivateKeyPkcs8 = privateKeyPkcs8;
        PublicKeySpki = publicKeySpki;
        PublicKeySignatureV2 = publicKeySignatureV2;
        ExpiresAtMs = expiresAtMs;
        RefreshedAfterMs = refreshedAfterMs;
    }

    /// <summary>
    /// Still usable at <paramref name="nowMs"/>: before Mojang's refresh time and
    /// at least 5 minutes from expiry (the service refuses an expired key, and
    /// the token it hands out must not be signed with one about to lapse).
    /// </summary>
    public bool FreshAt(long nowMs) => nowMs < RefreshedAfterMs && nowMs < ExpiresAtMs - 5 * 60_000;

    public override string ToString() => $"PlayerCertificate {{ ExpiresAtMs = {ExpiresAtMs} }}";
}

/// <summary>The Mojang-signed "textures" property of the player's profile.</summary>
public sealed record SignedTextures(string Value, string Signature);

/// <summary>
/// Identity v2 (docs/SOCIAL.md): everything the client proves itself with,
/// built from material Mojang already signs for every player, so the service
/// verifies it offline (Mojang refuses calls from Cloudflare Workers). Pure
/// parsing + signing; the network calls live in <see cref="SocialClient"/>.
/// </summary>
public static class MojangProof
{
    /// <summary>`origin-auth|&lt;uuid undashed lowercase&gt;|&lt;client&gt;|&lt;ms&gt;` — the exact string signed.</summary>
    public static string SignedString(string uuid, string client, long atMs) =>
        $"origin-auth|{Ids.Norm(uuid)}|{client}|{atMs.ToString(CultureInfo.InvariantCulture)}";

    /// <summary>Base64 RSASSA-PKCS1-v1_5 + SHA-256 signature of the UTF-8 text with the certificate's key.</summary>
    public static string Sign(PlayerCertificate certificate, string text)
    {
        using var rsa = RSA.Create();
        rsa.ImportPkcs8PrivateKey(certificate.PrivateKeyPkcs8, out _);
        var signature = rsa.SignData(Encoding.UTF8.GetBytes(text), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        return Convert.ToBase64String(signature);
    }

    /// <summary>
    /// The DER inside a PEM: header/footer lines and all whitespace stripped,
    /// base64-decoded. Mojang labels its keys "RSA PRIVATE/PUBLIC KEY" while
    /// the bodies are PKCS#8 / SPKI, so the label is ignored (Minecraft's own
    /// Crypt does the same). Null when it isn't base64 at all.
    /// </summary>
    public static byte[]? PemBody(string? pem)
    {
        if (string.IsNullOrWhiteSpace(pem)) return null;
        var body = Regex.Replace(pem, "-----(BEGIN|END)[^-]*-----", "");
        body = Regex.Replace(body, @"\s+", "");
        if (body.Length == 0) return null;
        try
        {
            return Convert.FromBase64String(body);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>
    /// The /player/certificates answer as a usable certificate, or null when
    /// any part is missing or a key doesn't load. A PKCS#1 body (should Mojang
    /// ever send what its label says) is converted, so signing always works.
    /// </summary>
    public static PlayerCertificate? ParseCertificate(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        try
        {
            using var doc = JsonDocument.Parse(body);
            var r = doc.RootElement;
            if (r.ValueKind != JsonValueKind.Object
                || !r.TryGetProperty("keyPair", out var pair) || pair.ValueKind != JsonValueKind.Object)
                return null;

            var privateKey = PrivateKeyPkcs8(PemBody(Str(pair, "privateKey")));
            var publicKey = PublicKeySpki(PemBody(Str(pair, "publicKey")));
            var keySignature = Str(r, "publicKeySignatureV2");
            var expiresAt = Millis(r, "expiresAt");
            if (privateKey == null || publicKey == null || string.IsNullOrWhiteSpace(keySignature) || expiresAt == null)
                return null;
            var refreshedAfter = Millis(r, "refreshedAfter") ?? expiresAt.Value;
            return new PlayerCertificate(privateKey, publicKey, keySignature.Trim(), expiresAt.Value, refreshedAfter);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>/session/minecraft/profile/&lt;uuid&gt;?unsigned=false → (name, textures), or null.</summary>
    public static (string? Name, SignedTextures Textures)? ParseProfile(string? body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;
        try
        {
            using var doc = JsonDocument.Parse(body);
            var r = doc.RootElement;
            if (r.ValueKind != JsonValueKind.Object
                || !r.TryGetProperty("properties", out var props) || props.ValueKind != JsonValueKind.Array)
                return null;
            foreach (var p in props.EnumerateArray())
            {
                if (p.ValueKind != JsonValueKind.Object || Str(p, "name") != "textures") continue;
                var value = Str(p, "value");
                var signature = Str(p, "signature");
                if (string.IsNullOrEmpty(value) || string.IsNullOrEmpty(signature)) return null;
                return (Str(r, "name"), new SignedTextures(value, signature));
            }
            return null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>
    /// Whether Mojang's refusal says the account isn't allowed online play
    /// (Microsoft family settings / multiplayer turned off), as opposed to any
    /// other failure.
    /// </summary>
    public static bool SaysMultiplayerDisabled(string? body) =>
        body != null && Regex.IsMatch(body, "multi-?player|privilege|parental", RegexOptions.IgnoreCase);

    /// <summary>
    /// An ISO-8601 instant as ms since epoch, truncated (never rounded) like
    /// Java's Instant.toEpochMilli() — the value Mojang signed. Fractions past
    /// 7 digits (Mojang sends up to 9) are cut before .NET parses them.
    /// </summary>
    public static long? IsoToEpochMs(string? iso)
    {
        if (string.IsNullOrWhiteSpace(iso)) return null;
        var text = Regex.Replace(iso.Trim(), @"(\.\d{7})\d+", "$1");
        return DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var at)
            ? at.ToUnixTimeMilliseconds()
            : null;
    }

    private static byte[]? PrivateKeyPkcs8(byte[]? der)
    {
        if (der == null) return null;
        try
        {
            using var rsa = RSA.Create();
            try
            {
                rsa.ImportPkcs8PrivateKey(der, out _);
                return der;
            }
            catch (CryptographicException)
            {
                rsa.ImportRSAPrivateKey(der, out _);
                return rsa.ExportPkcs8PrivateKey();
            }
        }
        catch (CryptographicException)
        {
            return null;
        }
    }

    private static byte[]? PublicKeySpki(byte[]? der)
    {
        if (der == null) return null;
        try
        {
            using var rsa = RSA.Create();
            try
            {
                rsa.ImportSubjectPublicKeyInfo(der, out _);
                return der; // the original bytes: exactly what Mojang signed
            }
            catch (CryptographicException)
            {
                rsa.ImportRSAPublicKey(der, out _);
                return rsa.ExportSubjectPublicKeyInfo();
            }
        }
        catch (CryptographicException)
        {
            return null;
        }
    }

    private static string? Str(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    /// <summary>An ISO string, or a number already in ms.</summary>
    private static long? Millis(JsonElement o, string name)
    {
        if (!o.TryGetProperty(name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt64(out var ms)) return ms;
        return v.ValueKind == JsonValueKind.String ? IsoToEpochMs(v.GetString()) : null;
    }
}
