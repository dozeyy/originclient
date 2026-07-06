using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace OriginLauncher.App.Core.Accounts;

public static class AccountStore
{
    private static readonly string FilePath = Path.Combine(OriginPaths.Accounts, "accounts.json");

    public static List<StoredAccount> Load()
    {
        if (!File.Exists(FilePath))
            return new List<StoredAccount>();

        try
        {
            var json = File.ReadAllText(FilePath);
            return JsonSerializer.Deserialize<List<StoredAccount>>(json) ?? new List<StoredAccount>();
        }
        catch (JsonException)
        {
            return new List<StoredAccount>();
        }
    }

    public static void Save(List<StoredAccount> accounts)
    {
        Directory.CreateDirectory(OriginPaths.Accounts);
        var json = JsonSerializer.Serialize(accounts, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(FilePath, json);
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
