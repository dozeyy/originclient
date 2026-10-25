using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Social;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI-only (UI/RenderShots.cs, after the top-bar shots): the account pill and the Store entry in every state the
/// brief names, plus the account drawer. Every player is made up (SocialDemo ids, no account store read or written, no
/// sign-in, no token): the pill and the drawer rows take their names straight from the harness.
///
///   16a short Microsoft name   16b a 16-letter name (the widest a real name gets) cut with "..."   16c a 23-letter name
///   16d offline test mode (warning dot)   16e signed out   16f the pill hovered   16g Store hovered   16h Store with
///   keyboard focus   16i the pill with keyboard focus   16j the account drawer   16k the narrowest window (960) with
///   a long name, so Store is icon-only   16l the same with an update waiting   16m the pill at 150 % scale.
/// account-fit.txt lists, per shot, the numbers the layout produced (pill size, head size, name cut or not).
/// </summary>
internal static class AccountShots
{
    // The widest letters a name can have, 16 of them (a Minecraft name is at most 16), and a 23-letter one.
    private const string Widest = "WWWWWWWWWWWWWWWW";
    private const string TwentyThree = "AVeryLongGamertagNameXX";

    internal static void Run(MainWindow window, string dir)
    {
        try
        {
            RunShots(window, dir);
        }
        catch (Exception e)
        {
            File.WriteAllText(Path.Combine(dir, "account-shots-error.txt"), e.ToString());
        }
        finally
        {
            window.TourShowAccount(null, "", false);
            window.TourShowUpdateBadge(false);
            window.TourSetAccountOpen(false);
        }
    }

    private static void RunShots(MainWindow window, string dir)
    {
        var log = new StringBuilder();
        var at = new Size(1180, 740);

        void Pose(string name, string? player, string uuid, bool offlineMode, Size? size = null, bool adorners = false)
        {
            window.TourShowAccount(player, uuid, offlineMode);
            Frame(window, dir, name, size ?? at, adorners, log);
        }

        window.TourShowUpdateBadge(false);
        window.TourGoTo(0);
        RenderShots.Pump(400);

        Pose("16a-pill-microsoft-short", "Steve", SocialDemo.Steve, false);
        Pose("16a2-pill-microsoft-alex", "Alex", SocialDemo.Alex, false);
        Pose("16b-pill-microsoft-long", Widest, SocialDemo.Steve, false);
        Pose("16c-pill-microsoft-23-letters", TwentyThree, SocialDemo.Alex, false);
        Pose("16d-pill-offline", null, "", true);
        Pose("16e-pill-signed-out", null, "", false);

        // Hover and focus: a render has no mouse and no keyboard, so the poses are set on the control (TourHover) and the
        // control's own focus frame is drawn the way WPF does it (ImportShots.FocusFrame).
        window.TourShowAccount("Steve", SocialDemo.Steve, false);
        window.TourHover(window.TourAccountButton, true);
        Frame(window, dir, "16f-pill-hover", at, false, log);
        window.TourHover(window.TourAccountButton, false);

        window.TourHover(window.TourStoreButton, true);
        window.TourShowLinkHints(true);
        Frame(window, dir, "16g-store-hover", at, false, log);
        window.TourShowLinkHints(false);
        window.TourHover(window.TourStoreButton, false);

        foreach (var (button, name) in new[] { (window.TourStoreButton, "16h-store-focus"), (window.TourAccountButton, "16i-pill-focus") })
        {
            window.TourLayoutAt(at);
            button.Focus();
            var undo = ImportShots.FocusFrame(window, button, name, log);
            Frame(window, dir, name, at, true, log);
            undo?.Invoke();
        }

        // The drawer, with made-up rows: the selected one, a second player, a half-added account and a name that gets cut.
        window.TourShowAccount("Steve", SocialDemo.Steve, false);
        window.TourSetAccountOpen(true);
        RenderShots.Pump(500);
        window.TourShowAccountRows(
        [
            new MinecraftAccount { Id = SocialDemo.Steve, Gamertag = "Steve", LastUsedUtc = DateTimeOffset.UtcNow, IsSelected = true },
            new MinecraftAccount { Id = SocialDemo.Alex, Gamertag = "Alex", LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-3) },
            new MinecraftAccount { Id = SocialDemo.Notch, Gamertag = "Notch", LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-9), IsPending = true },
            new MinecraftAccount { Id = SocialDemo.Kai, Gamertag = Widest, LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-30) },
        ]);
        RenderShots.Pump(300);
        window.TourLayoutAt(at);
        RenderShots.Snap(window, dir, "16j-drawer-accounts", size: at, laidOut: true);
        window.TourSetAccountOpen(false);
        RenderShots.Pump(400);

        // The narrowest window: the bar gives up the Store word and the wordmark (the long name keeps its 160).
        var narrow = new Size(960, 600);
        Pose("16k-bar-960-long-name", Widest, SocialDemo.Steve, false, narrow);
        window.TourShowUpdateBadge(true);
        Pose("16l-bar-960-long-name-update", Widest, SocialDemo.Steve, false, narrow);
        window.TourShowUpdateBadge(false);

        // 150 % scale: the head stays a whole number of screen pixels per face pixel.
        window.TourShowAccount("Steve", SocialDemo.Steve, false);
        RenderShots.SetTreeDpi(window, 1.5);
        try
        {
            Frame(window, dir, "16m-pill-150pct", at, false, log, dpi: 144);
        }
        finally
        {
            RenderShots.SetTreeDpi(window, 1.0);
        }

        File.WriteAllText(Path.Combine(dir, "account-fit.txt"), log.ToString());
    }

    private static void Frame(MainWindow window, string dir, string name, Size size, bool adorners, StringBuilder log, double dpi = 96)
    {
        window.TourLayoutAt(size);
        RenderShots.Pump(150);
        log.AppendLine($"{name}: {window.TourAccountFit()}");
        RenderShots.Snap(window, dir, name, dpi: dpi, size: size, adorners: adorners, laidOut: true);
    }
}
