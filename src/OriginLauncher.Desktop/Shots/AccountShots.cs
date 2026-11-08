using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Social;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shots;

// The account drawer and the Microsoft sign-in card, named like the Windows set (UI/AccountShots.cs, RenderShots.cs).
// The sign-in card never starts its web view or the network in a render run: each shot sets the state it shows.
public static class AccountShots
{
    private const string LongName = "WWWWWWWWWWWWWWWW";

    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        yield return new Shot("12-accounts", w => Drawer(w), Seed: () => Demo.Version("1.21.1"));
        yield return new Shot("16j-drawer-accounts", w => Drawer(w), Seed: FourAccounts);
        yield return new Shot("16k-drawer-accounts-hover", w =>
        {
            var drawer = Drawer(w);
            drawer.Rows[1].ForceHover = true;
            drawer.Rows[2].ForceFocus = true;
        }, Seed: FourAccounts);
        yield return new Shot("16l-drawer-accounts-note", w =>
            Drawer(w).ShowNote("Microsoft is limiting sign-ins right now. Your account is saved: in a few minutes, click it in Accounts to finish signing in.", error: false),
            Seed: FourAccounts);
        yield return new Shot("16m-drawer-accounts-error", w =>
        {
            var drawer = Drawer(w);
            drawer.ShowNote("This sign-in expired. Please add the account again.", error: true);
            drawer.SetBusy(true);
        }, Seed: FourAccounts);

        yield return new Shot("06b-sign-in", w => SignIn(w).ShowLoading());
        yield return new Shot("06c-sign-in-message", w => SignIn(w).ShowMessage("Couldn't add this account", "Couldn't save this account. Please try again."));
        // Microsoft's page can't open in the card (no WebKitGTK, a broken one): sign in with a code (Windows' name).
        yield return new Shot("06d-sign-in-code", w => SignIn(w).TourShowCode("ABCD-EFGH", "https://www.microsoft.com/link"));
        // Update clicked while a game is open: the scene waits for Minecraft to close, with Cancel and the top bar.
        yield return new Shot("06e-update-waiting-for-game", w => UpdatePrompt.TourScene(w, "1.0.99", waitingForGame: true));
        yield return new Shot("06e2-update-downloading", w => UpdatePrompt.TourScene(w, "1.0.99", waitingForGame: false));
        yield return new Shot("06e3-update-failed-chip", w => w.Bar.SetUpdateNote("Update failed - retry", Palette.Warning));
        yield return new Shot("06f-sign-in-finishing", w => SignIn(w).ShowLoading("Finishing sign-in..."));
        yield return new Shot("dpi-sign-in-loading-1180x740-100pct", w => SignIn(w).ShowLoading());
        yield return new Shot("dpi-sign-in-message-1180x740-100pct", w =>
            SignIn(w).ShowMessage("Couldn't add this account", "Couldn't save this account. Please try again."));
    }

    private static AccountDrawer Drawer(MainWindow w)
    {
        var drawer = new AccountDrawer(w);
        w.OpenDrawer(drawer);
        return drawer;
    }

    private static SignInPanel SignIn(MainWindow w)
    {
        Drawer(w);
        return SignInPanel.Open(w);
    }

    /// <summary>The Windows 16j list: active Steve, Alex, a pending Notch, and a name as wide as names get.</summary>
    private static void FourAccounts()
    {
        var now = DateTimeOffset.UtcNow;
        AccountStore.Save(
        [
            new StoredAccount { Id = SocialDemo.Steve, Gamertag = "Steve", IsSelected = true, LastUsedUtc = now, ProtectedRefreshToken = "demo" },
            new StoredAccount { Id = SocialDemo.Alex, Gamertag = "Alex", LastUsedUtc = now.AddDays(-3), ProtectedRefreshToken = "demo" },
            new StoredAccount
            {
                Id = SocialDemo.Notch, Gamertag = "Notch", LastUsedUtc = now.AddDays(-9), ProtectedRefreshToken = "demo",
                PendingMinecraftSignIn = true,
            },
            new StoredAccount { Id = SocialDemo.Kai, Gamertag = LongName, LastUsedUtc = now.AddDays(-30), ProtectedRefreshToken = "demo" },
        ]);
        Demo.Version("1.21.1");
    }
}
