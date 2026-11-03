using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Auth.Sessions;

namespace OriginLauncher.App.Core.Accounts;

// The token-at-rest encryption is the per-OS part (one file per OS, picked by each csproj):
// AccountStore.Windows.cs = DPAPI, AccountStore.Mac.cs = AES-GCM with its key in the login
// Keychain. Either way a blob that can't be decrypted throws CryptographicException, which
// every reader below turns into "not signed in" - never a plaintext fallback.
public static partial class AccountStore
{
    // The real file. Tests point it at a temporary file (and swap the platform
    // calls below), so nothing ever touches the player's real accounts.
    internal static string FilePath { get; set; } = Path.Combine(OriginPaths.Accounts, "accounts.json");

    internal static Func<byte[], byte[]> ProtectBytes { get; set; } = PlatformProtect;

    internal static Func<byte[], byte[]> UnprotectBytes { get; set; } = PlatformUnprotect;
    private static readonly object Gate = new();

    public static List<StoredAccount> Load()
    {
        lock (Gate)
        {
            try
            {
                return ReadCore();
            }
            catch (JsonException)
            {
                return new List<StoredAccount>();
            }
            catch (IOException)
            {
                return new List<StoredAccount>();
            }
            catch (UnauthorizedAccessException)
            {
                return new List<StoredAccount>();
            }
        }
    }

    public static void Save(List<StoredAccount> accounts)
    {
        lock (Gate)
            SaveCore(accounts);
    }

    // Account data is mutated by both the long-lived switcher and silent token
    // refreshes. Always reload inside the same lock before changing it so an old
    // panel snapshot cannot put a rotated Microsoft refresh token back on disk.
    public static List<StoredAccount> Update(Func<List<StoredAccount>, List<StoredAccount>> mutate)
    {
        lock (Gate)
        {
            var updated = mutate(ReadCore());
            SaveCore(updated);
            return updated;
        }
    }

    private static List<StoredAccount> ReadCore()
    {
        if (!File.Exists(FilePath))
            return new List<StoredAccount>();

        var json = File.ReadAllText(FilePath);
        return JsonSerializer.Deserialize<List<StoredAccount>>(json) ?? new List<StoredAccount>();
    }

    private static void SaveCore(List<StoredAccount> accounts)
    {
        var folder = Path.GetDirectoryName(FilePath)!;
        Directory.CreateDirectory(folder);
        var json = JsonSerializer.Serialize(accounts, new JsonSerializerOptions { WriteIndented = true });
        var tempPath = Path.Combine(
            folder,
            $"accounts.{Environment.ProcessId}.{Guid.NewGuid():N}.tmp");

        try
        {
            File.WriteAllText(tempPath, json);
            File.Move(tempPath, FilePath, true);
        }
        finally
        {
            try
            {
                File.Delete(tempPath);
            }
            catch (IOException)
            {
                // A completed replace is what matters. A locked orphaned temp
                // file is harmless and can be cleaned up on a later run.
            }
            catch (UnauthorizedAccessException)
            {
            }
        }
    }

    // Adds the account if new, or updates it in place if it already exists
    // (re-signing in refreshes the gamertag/token). Marks it as the sole
    // selected account either way — signing in always makes that account active.
    public static List<StoredAccount> Upsert(List<StoredAccount> accounts, StoredAccount account)
    {
        var existing = accounts.FirstOrDefault(a => a.Id == account.Id);
        if (existing != null)
            accounts.Remove(existing);

        foreach (var a in accounts)
            a.IsSelected = false;
        account.IsSelected = true;

        accounts.Add(account);
        return accounts;
    }

    public static void SetSelected(List<StoredAccount> accounts, string accountId)
    {
        foreach (var a in accounts)
            a.IsSelected = a.Id == accountId;
    }

    // Removing an account from this launcher (the trash button in Accounts, Windows and Linux): its row goes, and
    // with it the only copies of its tokens (the refresh token and the cached Minecraft session live inside the
    // row). Its game folders (worlds, settings, servers under instances/<version>/accounts/<uuid>/) are left on
    // disk on purpose, so adding the account again picks them back up. If it was the active account, the most
    // recently used account that can play takes over (a pending one never may: GetSelected).
    public static List<StoredAccount> Remove(List<StoredAccount> accounts, string accountId)
    {
        var wasSelected = accounts.Any(a => a.Id == accountId && a.IsSelected);
        accounts.RemoveAll(a => a.Id == accountId);
        if (wasSelected && accounts.Where(a => !a.PendingMinecraftSignIn)
                .OrderByDescending(a => a.LastUsedUtc).FirstOrDefault() is { } next)
            SetSelected(accounts, next.Id);
        return accounts;
    }

    // A pending account (still finishing its Minecraft sign-in) is never the
    // selected one: nothing may play, migrate folders or sign in to friends
    // as a placeholder id.
    public static StoredAccount? GetSelected(List<StoredAccount> accounts) =>
        accounts.FirstOrDefault(a => a.IsSelected && !a.PendingMinecraftSignIn);

    public static string ProtectRefreshToken(string refreshToken)
    {
        var bytes = Encoding.UTF8.GetBytes(refreshToken);
        var protectedBytes = ProtectBytes(bytes);
        return Convert.ToBase64String(protectedBytes);
    }

    // The cached Minecraft session gets the same protection as the
    // refresh token — it is a bearer token, never written in plaintext.
    public static string ProtectSession(MinecraftSession session) =>
        ProtectRefreshToken(JsonSerializer.Serialize(session));

    // Null when missing, unreadable or malformed: "no saved session", so the
    // broker simply signs in when it next needs one.
    public static MinecraftSession? TryUnprotectSession(string? protectedSession)
    {
        if (string.IsNullOrEmpty(protectedSession)) return null;
        var json = TryUnprotectRefreshToken(protectedSession);
        if (json == null) return null;
        try
        {
            var session = JsonSerializer.Deserialize<MinecraftSession>(json);
            return session is { AccessToken.Length: > 0, Uuid.Length: > 0 } ? session : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    // Returns null (rather than throwing) if the blob is corrupt or was
    // encrypted under a different Windows profile / Mac key — callers treat that the
    // same as "not signed in" and fall back to an interactive login.
    public static string? TryUnprotectRefreshToken(string protectedToken)
    {
        try
        {
            return ReadRefreshToken(protectedToken);
        }
        catch (KeyStoreLockedException)
        {
            return null;
        }
    }

    // The sign-in's read (AccountSessionVault.RefreshToken): null like TryUnprotectRefreshToken for a blob that can't
    // be opened, but a locked Linux keyring / Mac Keychain throws KeyStoreLockedException, so Play can say "unlock it"
    // instead of "add this account again".
    public static string? ReadRefreshToken(string protectedToken)
    {
        try
        {
            var protectedBytes = Convert.FromBase64String(protectedToken);
            var bytes = UnprotectBytes(protectedBytes);
            return Encoding.UTF8.GetString(bytes);
        }
        catch (KeyStoreLockedException)
        {
            throw;
        }
        catch (CryptographicException)
        {
            return null;
        }
        catch (FormatException)
        {
            return null;
        }
    }
}
