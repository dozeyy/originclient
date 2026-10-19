using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Promos;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI-only: `OriginLauncher.App.exe --render-shots &lt;folder&gt;` opens the real
/// main window with made-up social data (Core/Social/SocialDemo) and saves
/// pictures of Home (also its Play button in the "Running" state, and the launch
/// progress screen) and the Friends drawer, so launcher UI changes are looked
/// at before they ship (.github/workflows/launcher-shots.yml). Nothing is
/// signed in to and the social service never touches the network.
/// </summary>
public static class RenderShots
{
    public static void Run(string outDir)
    {
        Directory.CreateDirectory(outDir);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        SocialHub.OnlineCountOverride = 1284;
        SocialHub.Service.ShowDemo(SocialDemo.Online(now));

        var window = new MainWindow
        {
            ShowActivated = false,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Left = 0,
            Top = 0,
        };
        window.Show();
        Pump(2500); // first layout, fonts, the players pill's idle fetch

        Snap(window, outDir, "01-home");

        window.TourSetFriendsOpen(true);
        Pump(600);
        Snap(window, outDir, "02-friends");

        window.TourFriendsPanel.OpenConversation(SocialDemo.Steve);
        Pump(600);
        Snap(window, outDir, "03-chat");

        window.TourFriendsPanel.OpenConversation(SocialSnapshot.PartyKey);
        Pump(600);
        Snap(window, outDir, "04-party-chat");

        window.TourFriendsPanel.HandleEscape();
        window.TourSetFriendsOpen(false);
        Pump(400);
        foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
        Pump(800);
        Snap(window, outDir, "05-toasts");
        window.TourClearToasts();

        SocialHub.Service.ShowDemo(new SocialSnapshot
        {
            Status = SocialStatus.NotSignedIn,
            StatusMessage = "Sign in with Microsoft to use friends.",
        });
        window.TourSetFriendsOpen(true);
        Pump(600);
        Snap(window, outDir, "06-signed-out");

        // The Play press: the plain launch progress screen, first with a stage
        // that has no count (the sliding block), then one with real counts (the fill).
        window.TourSetFriendsOpen(false);
        Pump(400);
        window.TourShowLaunch("1.21.1");
        Pump(500);
        Snap(window, outDir, "08-launch-a-preparing");
        window.TourReportLaunch("Downloading libraries (240/600)");
        Pump(900);
        Snap(window, outDir, "08-launch-b-counted");
        window.TourHideLaunch();
        Pump(500);

        // Mod profiles, with made-up profiles (the real settings are never touched):
        // the Profiles page, its new-profile form, then Home with a profile that
        // has a server (profile row + the Join button under Play).
        var demoProfiles = new LauncherSettings
        {
            ModProfiles =
            {
                new ModProfile { Id = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Survival SMP", Version = "1.21.1", ServerName = "Survival SMP", ServerAddress = "play.example.net" },
                new ModProfile { Id = "1a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "PvP practice", Version = "1.8.9", ServerName = "Practice", ServerAddress = "pvp.example.org:25570" },
                new ModProfile { Id = "2a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Singleplayer shaders", Version = "1.20.1" },
            },
            SelectedProfileId = "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
            SelectedVersion = "1.21.1",
        };
        Func<ModProfile, int> demoCount = p => p.Name.StartsWith("PvP", StringComparison.Ordinal) ? 1 : p.HasServer ? 6 : 3;
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        Pump(700);
        Snap(window, outDir, "09-profiles");
        window.TourShowProfiles(demoProfiles, demoCount, openForm: true);
        Pump(500);
        Snap(window, outDir, "10-profiles-form");
        window.TourShowHomeWithProfile(demoProfiles.ModProfiles[0]);
        Pump(700);
        Snap(window, outDir, "11-home-profile");

        // The selected account's game is already open: Play reads "Running"
        // and brings that window forward (one game per account).
        window.TourSetFriendsOpen(false);
        window.TourShowRunning("Steve");
        Pump(600);
        Snap(window, outDir, "07-play-running");

        SnapBanners(window, outDir);

        // Import from another launcher: every state of the card, with a made-up backend.
        ImportShots.Run(window, outDir);

        window.Close();
    }

    /// <summary>
    /// The home banner card by card at 2x — every tip, the feed's own cards,
    /// and a card at the feed's longest allowed title/subtitle — to show that
    /// card text always fits whole. A failure here is written to
    /// banner-error.txt and doesn't cost the other screens.
    /// </summary>
    private static void SnapBanners(Window window, string dir)
    {
        try
        {
            var banner = FindVisual<Controls.PromoBanner>(window)
                         ?? throw new InvalidOperationException("home banner not found");
            var slides = PromoRotation.Playable(PromoFeedLoader.BuiltIn().Feed, discordReady: false).ToList();
            var tips = slides.FindIndex(p => p.Kind == PromoKind.Tips);
            for (var t = 0; t < PromoTips.All.Count && tips >= 0; t++)
            {
                banner.TourShowCard(slides, tips, t);
                Pump(500);
                SnapElement(window, banner, dir, $"07-banner-tip-{PromoTips.All[t].Id}");
            }
            for (var i = 0; i < slides.Count; i++)
            {
                if (slides[i].Kind != PromoKind.Link) continue;
                banner.TourShowCard(slides, i, 0);
                Pump(500);
                SnapElement(window, banner, dir, $"07-banner-{slides[i].Id}");
            }

            var longest = new HousePromo("longest", PromoKind.Link, "ANNOUNCEMENT",
                // Exactly PromoFeedParser.MaxTitleLength / MaxSubtitleLength characters.
                "Origin 2.0 is here with brand new menus, maps and more cosmetics",
                "Everything you asked for in one update: smoother shaders, a faster launcher and new capes to get",
                null, new Uri("https://originclient.org/"));
            banner.TourShowCard(slides.Append(longest).ToList(), slides.Count, 0);
            Pump(500);
            SnapElement(window, banner, dir, "07-banner-longest-allowed");
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(dir, "banner-error.txt"), ex.ToString());
        }
    }

    private static T? FindVisual<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T match) return match;
            if (FindVisual<T>(child) is { } found) return found;
        }
        return null;
    }

    // One element at 2x on the window's background. Drawn through a
    // VisualBrush so its position in the window doesn't offset the picture.
    private static void SnapElement(Window window, FrameworkElement element, string dir, string name)
    {
        window.UpdateLayout();
        var size = new Rect(0, 0, element.ActualWidth, element.ActualHeight);
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRectangle(window.Background, null, size);
            dc.DrawRectangle(new VisualBrush(element) { Stretch = Stretch.None, AlignmentX = AlignmentX.Left, AlignmentY = AlignmentY.Top },
                null, size);
        }
        var bitmap = new RenderTargetBitmap((int)Math.Ceiling(size.Width * 2), (int)Math.Ceiling(size.Height * 2),
            192, 192, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }

    /// <summary>Runs the dispatcher for a while so layout, bindings and animations settle.</summary>
    internal static void Pump(int milliseconds)
    {
        var frame = new DispatcherFrame();
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(milliseconds) };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            frame.Continue = false;
        };
        timer.Start();
        Dispatcher.PushFrame(frame);
    }

    internal static void Snap(Window window, string dir, string name)
    {
        window.UpdateLayout();
        var root = (FrameworkElement)window.Content;
        var bitmap = new RenderTargetBitmap((int)root.ActualWidth, (int)root.ActualHeight, 96, 96, PixelFormats.Pbgra32);
        // The window's own background sits behind its content.
        var backed = new DrawingVisual();
        using (var dc = backed.RenderOpen())
        {
            dc.DrawRectangle(window.Background, null, new Rect(0, 0, root.ActualWidth, root.ActualHeight));
        }
        bitmap.Render(backed);
        bitmap.Render(root);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }
}
