using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// Friends, chat, party chat and the social toasts, named like the Windows set (UI/SocialShots.cs). The social
// service runs in demo mode (ShowDemo: no network); the friends are Core's own SocialDemo set.
public static class SocialShots
{
    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        yield return new Shot("02-friends", w => Friends(w, Online()), Seed: Signed);
        yield return new Shot("02b-friends-add", w =>
        {
            var d = Friends(w, Online());
            d.ShowAdd("Dinnerbone");
            d.ShowNotice("Friend request sent to jeb_.", error: false);
        }, Seed: Signed);
        yield return new Shot("02c-friends-error", w =>
            Friends(w, Online()).ShowNotice("Steve isn't on a server right now.", error: true), Seed: Signed);
        yield return new Shot("02d-friends-empty", w => Friends(w, new SocialSnapshot
        {
            Status = SocialStatus.Online, HasData = true, Me = new SocialMe(SocialDemo.Me, "OriginPlayer", false),
        }), Seed: Signed);
        yield return new Shot("02e-friends-unreachable", w => Friends(w, new SocialSnapshot { Status = SocialStatus.Unreachable }), Seed: Signed);
        yield return new Shot("02f-friends-stale", w => Friends(w, Online() with { Status = SocialStatus.Unreachable }), Seed: Signed);
        yield return new Shot("03-chat", w => Friends(w, Online()).OpenChat(SocialDemo.Steve), Seed: Signed);
        yield return new Shot("03b-chat-empty", w => Friends(w, Online()).OpenChat(SocialDemo.Notch), Seed: Signed);
        yield return new Shot("04-party-chat", w => Friends(w, Online()).OpenChat(SocialSnapshot.PartyKey), Seed: Signed);
        yield return new Shot("05-toasts", w =>
        {
            Show(w, Online());
            SocialToasts.Of(w).ShowAll(SocialDemo.Toasts());
        }, Seed: Signed);
        yield return new Shot("06-signed-out", w => Friends(w, new SocialSnapshot
        {
            Status = SocialStatus.NotSignedIn, StatusMessage = "Sign in with Microsoft to use friends.",
        }));
        yield return new Shot("dpi-home-friends-toasts-1180x740-100pct", w =>
        {
            Friends(w, Online());
            SocialToasts.Of(w).ShowAll(SocialDemo.Toasts());
        }, Seed: Signed);
    }

    private static void Signed() => Shots.Demo.SignIn();

    private static SocialSnapshot Online() => SocialDemo.Online(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

    private static void Show(MainWindow w, SocialSnapshot snapshot)
    {
        SocialHub.Service.ShowDemo(snapshot);
        FriendsDrawer.Wire(w);
    }

    private static FriendsDrawer Friends(MainWindow w, SocialSnapshot snapshot)
    {
        Show(w, snapshot);
        return FriendsDrawer.OpenOn(w);
    }
}
