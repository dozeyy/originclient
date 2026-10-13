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

    [Fact]
    public void The_test_never_points_at_the_real_accounts_file()
    {
        Assert.StartsWith(Path.GetTempPath(), AccountStore.FilePath);
        Assert.Equal("x", Encoding.UTF8.GetString(AccountStore.ProtectBytes(Encoding.UTF8.GetBytes("x"))));
    }
}
