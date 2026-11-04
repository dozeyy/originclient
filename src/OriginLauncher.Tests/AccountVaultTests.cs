using System.Text;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Auth;
using OriginLauncher.App.Core.Auth.Sessions;
using Xunit;

namespace OriginLauncher.Tests;

// The real accounts.json vault behind MinecraftSessions (AccountSessionVault +
// AccountStore), against a temporary accounts.json. DPAPI is swapped for a
// pass-through so this runs anywhere (CI is Linux); the player's real file is
// never touched: AccountStore.FilePath points into a fresh temp folder.
public sealed class AccountVaultTests : IDisposable
{
    private const string Alex = "853c80ef3c3749fdaa49938b674adae6";

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-vault-" + Guid.NewGuid().ToString("N"));
    private readonly string _realPath = AccountStore.FilePath;
    private readonly Func<byte[], byte[]> _realProtect = AccountStore.ProtectBytes;
    private readonly Func<byte[], byte[]> _realUnprotect = AccountStore.UnprotectBytes;
    private readonly AccountSessionVault _vault = new();

    public AccountVaultTests()
    {
        Directory.CreateDirectory(_dir);
        AccountStore.FilePath = Path.Combine(_dir, "accounts.json");
        AccountStore.ProtectBytes = bytes => bytes;
        AccountStore.UnprotectBytes = bytes => bytes;
    }

    public void Dispose()
    {
        AccountStore.FilePath = _realPath;
        AccountStore.ProtectBytes = _realProtect;
        AccountStore.UnprotectBytes = _realUnprotect;
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    private static StoredAccount Row(string id, string refresh, bool pending = false) => new()
    {
        Id = id,
        Gamertag = "Alex",
        ProtectedRefreshToken = AccountStore.ProtectRefreshToken(refresh),
        ProtectedMinecraftSession = AccountStore.ProtectSession(
            new MinecraftSession("Alex", id, "token", "xuid", DateTimeOffset.UtcNow.AddHours(20))),
        PendingMinecraftSignIn = pending,
    };

    [Fact]
    public void Unlink_clears_the_sign_in_and_the_session_so_nothing_can_sign_in_again()
    {
        AccountStore.Save([Row(Alex, "rt-0")]);
        Assert.Equal("rt-0", _vault.RefreshToken(Alex));
        Assert.NotNull(_vault.SavedSession(Alex));

        _vault.Unlink(Alex, "rt-0");

        Assert.Null(_vault.RefreshToken(Alex)); // an emptied token is "none", never ""
        Assert.Null(_vault.SavedSession(Alex));
        Assert.Single(AccountStore.Load()); // the row itself stays (re-add replaces it)
    }

    [Fact]
    public void Unlink_keeps_a_row_that_was_added_again_with_a_new_token()
    {
        AccountStore.Save([Row(Alex, "rt-fresh")]);

        _vault.Unlink(Alex, "rt-0"); // the refused sign-in used the OLD token

        Assert.Equal("rt-fresh", _vault.RefreshToken(Alex));
        Assert.NotNull(_vault.SavedSession(Alex));
    }

    [Fact]
    public void Unlink_never_touches_a_pending_account()
    {
        const string pending = MinecraftSessions.PendingIdPrefix + "1";
        AccountStore.Save([Row(pending, "rt-0", pending: true)]);

        _vault.Unlink(pending, "rt-0");

        Assert.Equal("rt-0", _vault.RefreshToken(pending));
    }

    // A locked Linux keyring / Mac Keychain (or its dismissed unlock prompt) used to read as "no sign-in", so Play said
    // "Your sign-in is no longer valid. Add this account again" when unlocking was the whole fix.
    [Fact]
    public void A_locked_key_store_tells_Play_to_unlock_it_not_to_re_add_the_account()
    {
        AccountStore.Save([Row(Alex, "rt-0")]);
        AccountStore.UnprotectBytes = _ => throw new KeyStoreLockedException("The keyring is locked.");

        var ex = Assert.Throws<MicrosoftAuthException>(() => _vault.RefreshToken(Alex));

        Assert.Equal(MicrosoftAuthException.KeyStoreLockedStage, ex.Stage);
        Assert.Equal(MicrosoftAuthException.KeyStoreLockedMessage(OperatingSystem.IsMacOS()), ex.Message);
        Assert.Equal("The keyring is locked.", ex.Detail); // the store's own words only reach the error log
        Assert.Null(_vault.SavedSession(Alex)); // the cached session is simply "none": the sign-in read says why
        Assert.Single(AccountStore.Load()); // nothing about the row changed
    }

    [Fact]
    public void A_blob_that_cannot_be_opened_is_still_no_sign_in()
    {
        AccountStore.Save([Row(Alex, "rt-0")]);
        AccountStore.UnprotectBytes = _ => throw new System.Security.Cryptography.CryptographicException("wrong key");

        Assert.Null(_vault.RefreshToken(Alex));
        Assert.Null(AccountStore.TryUnprotectRefreshToken(AccountStore.Load()[0].ProtectedRefreshToken));
    }

    [Fact]
    public void The_locked_message_names_each_OS_store_and_says_what_to_do()
    {
        Assert.Equal("Your keyring is locked: unlock it and press Play again.", MicrosoftAuthException.KeyStoreLockedMessage(mac: false));
        Assert.Equal("Your Keychain is locked: unlock it and press Play again.", MicrosoftAuthException.KeyStoreLockedMessage(mac: true));
    }

    // KD-1 (bug run 6): a power cut right after a save can leave accounts.json empty or full of NUL bytes. That used
    // to sign the player out AND refuse every later sign-in ("Couldn't save this account") until they deleted the
    // file by hand. Now the damaged file is kept aside and the sign-in saves.
    [Theory]
    [InlineData("")]
    [InlineData("\0\0\0\0")]
    [InlineData("[{\"Id\":\"853c")]
    [InlineData("null")]
    public void A_damaged_accounts_file_is_kept_aside_and_the_next_sign_in_saves(string damaged)
    {
        File.WriteAllText(AccountStore.FilePath, damaged);

        Assert.Empty(AccountStore.Load()); // shown as signed out...
        Assert.True(File.Exists(AccountStore.FilePath)); // ...but a read alone never moves or rewrites the file

        AccountStore.Update(list => AccountStore.Upsert(list, Row(Alex, "rt-1")));

        Assert.Equal(Alex, AccountStore.GetSelected(AccountStore.Load())!.Id);
        Assert.Equal("rt-1", _vault.RefreshToken(Alex));
        var kept = Assert.Single(Directory.GetFiles(_dir, "accounts.json.damaged-*"));
        Assert.Equal(damaged, File.ReadAllText(kept)); // byte for byte, for the player or support
    }

    [Fact]
    public void A_change_that_fails_on_a_damaged_file_leaves_it_where_it_was()
    {
        File.WriteAllText(AccountStore.FilePath, "\0\0");

        Assert.Throws<InvalidOperationException>(() =>
            AccountStore.Update(_ => throw new InvalidOperationException("sealing failed")));

        Assert.Equal("\0\0", File.ReadAllText(AccountStore.FilePath));
        Assert.Empty(Directory.GetFiles(_dir, "accounts.json.damaged-*"));
    }

    [Fact]
    public void A_readable_file_is_never_set_aside()
    {
        AccountStore.Save([Row(Alex, "rt-0")]);

        AccountStore.Update(list => list);

        Assert.Empty(Directory.GetFiles(_dir, "accounts.json.damaged-*"));
        Assert.Equal("rt-0", _vault.RefreshToken(Alex));
    }

    [Fact]
    public void A_refused_accounts_write_reaches_the_player_in_plain_words()
    {
        var ex = Assert.Throws<MicrosoftAuthException>(() =>
            MicrosoftAuthException.Stored(() => throw new IOException(AccountStore.BusyMessage)));

        Assert.Equal(MicrosoftAuthException.AccountStoreStage, ex.Stage);
        Assert.Equal(AccountStore.BusyMessage, ex.Message);
    }

    [Fact]
    public void The_test_never_points_at_the_real_accounts_file()
    {
        Assert.StartsWith(Path.GetTempPath(), AccountStore.FilePath);
        Assert.Equal("x", Encoding.UTF8.GetString(AccountStore.ProtectBytes(Encoding.UTF8.GetBytes("x"))));
    }
}
