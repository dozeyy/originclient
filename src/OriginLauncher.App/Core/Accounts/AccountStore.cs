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

    // A read another program blocks for a moment (antivirus, backup, indexer): three short waits (~0.2 s).
    private static readonly int[] ReadWaitsMs = { 25, 60, 120 };

    private enum ReadState { Ok, Missing, Damaged, Unreadable }

    /// <summary>
    /// The player's line when accounts.json can't be read even after the short waits. Update throws an
    /// <see cref="IOException"/> with it and leaves the file exactly as it was.
    /// </summary>
    public const string BusyMessage =
        "Origin couldn't open its saved accounts: another program (antivirus or a backup tool) is using the file. Wait a moment and try again.";

    // The accounts on disk now. A missing, damaged or unreadable file reads as "no accounts" (for display only:
    // Update re-reads and never writes over a file it couldn't read).
    public static List<StoredAccount> Load()
    {
        lock (Gate)
            return Read().Accounts;
    }

    public static void Save(List<StoredAccount> accounts)
    {
        lock (Gate)
            SaveCore(accounts);
    }

    // Account data is mutated by both the long-lived switcher and silent token
    // refreshes. Always reload inside the same lock before changing it so an old
    // panel snapshot cannot put a rotated Microsoft refresh token back on disk.
    //
    // Fail closed, the same rules as SettingsStore: a file another program holds is retried briefly, then the write
    // is refused (IOException, BusyMessage) and the file stays as it was. A file that reads but isn't a valid account
    // list (a power cut right after a save can leave it empty or full of NUL bytes) can never be repaired by waiting:
    // it is kept beside the real one as accounts.json.damaged-<time> and the change starts from an empty list, so
    // the next sign-in saves. Before 2026-11-04 Update threw on such a file, so every sign-in failed with "Couldn't
    // save this account" until the player deleted accounts.json by hand. The tokens inside the kept copy stay
    // encrypted (DPAPI / Keychain / keyring) exactly as they were; nothing is decrypted to move it.
    public static List<StoredAccount> Update(Func<List<StoredAccount>, List<StoredAccount>> mutate)
    {
        lock (Gate)
        {
            var (accounts, state) = Read();
            if (state == ReadState.Unreadable)
                throw new IOException(BusyMessage);
            var updated = mutate(accounts);
            // Set the damaged file aside only now that a replacement is ready: a mutate that throws must leave
            // accounts.json exactly where it was, not missing.
            if (state == ReadState.Damaged) KeepDamagedCopy();
            SaveCore(updated);
            return updated;
        }
    }

    private static (List<StoredAccount> Accounts, ReadState State) Read()
    {
        for (var attempt = 0; ; attempt++)
        {
            try
            {
                if (!File.Exists(FilePath)) return (new List<StoredAccount>(), ReadState.Missing);
                // Share everything: this read must never be the handle that makes another writer's rename fail.
                string json;
                using (var stream = new FileStream(FilePath, FileMode.Open, FileAccess.Read,
                           FileShare.ReadWrite | FileShare.Delete))
                using (var reader = new StreamReader(stream))
                    json = reader.ReadToEnd();
                var accounts = JsonSerializer.Deserialize<List<StoredAccount>>(json);
                // "null" (or a list holding nulls) is not something SaveCore ever writes: damaged, not empty.
                return accounts == null || accounts.Any(a => a == null)
                    ? (new List<StoredAccount>(), ReadState.Damaged)
                    : (accounts, ReadState.Ok);
            }
            catch (JsonException)
            {
                // Also the 0-byte and NUL-filled files a power cut leaves behind.
                return (new List<StoredAccount>(), ReadState.Damaged);
            }
            catch (FileNotFoundException)
            {
                return (new List<StoredAccount>(), ReadState.Missing); // deleted between the check and the open
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                if (attempt >= ReadWaitsMs.Length) return (new List<StoredAccount>(), ReadState.Unreadable);
                Thread.Sleep(ReadWaitsMs[attempt]);
            }
        }
    }

    // Renamed aside, never deleted: the player (or support) can still recover it. Throws when it can't be moved,
    // which refuses the write rather than lose the file.
    private static void KeepDamagedCopy()
    {
        var aside = $"{FilePath}.damaged-{DateTime.Now:yyyyMMdd-HHmmss}";
        for (var n = 2; File.Exists(aside); n++) aside = $"{FilePath}.damaged-{DateTime.Now:yyyyMMdd-HHmmss}-{n}";
        File.Move(FilePath, aside);
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
            // Flushed to the disk before the rename: without it NTFS can keep the rename and lose the data after a
            // power cut, which is exactly how an empty or NUL-filled accounts.json is made.
            using (var stream = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                       4096, FileOptions.WriteThrough))
            {
                var bytes = new UTF8Encoding(false).GetBytes(json);
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(flushToDisk: true);
            }
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
