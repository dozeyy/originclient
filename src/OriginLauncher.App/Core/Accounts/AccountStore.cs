using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace OriginLauncher.App.Core.Accounts;

public static class AccountStore
{
    private static readonly string FilePath = Path.Combine(OriginPaths.Accounts, "accounts.json");
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
        Directory.CreateDirectory(OriginPaths.Accounts);
        var json = JsonSerializer.Serialize(accounts, new JsonSerializerOptions { WriteIndented = true });
        var tempPath = Path.Combine(
            OriginPaths.Accounts,
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

    public static StoredAccount? GetSelected(List<StoredAccount> accounts) =>
        accounts.FirstOrDefault(a => a.IsSelected);

    public static string ProtectRefreshToken(string refreshToken)
    {
        var bytes = Encoding.UTF8.GetBytes(refreshToken);
        var protectedBytes = ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(protectedBytes);
    }

    // Returns null (rather than throwing) if the blob is corrupt or was
    // encrypted under a different Windows profile — callers treat that the
    // same as "not signed in" and fall back to an interactive login.
    public static string? TryUnprotectRefreshToken(string protectedToken)
    {
        try
        {
            var protectedBytes = Convert.FromBase64String(protectedToken);
            var bytes = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
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
