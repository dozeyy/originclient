using System.IO;
using System.Windows;
using System.Windows.Controls;
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
        // `--social`: only the Friends / party / chat surfaces, every state (UI/SocialShots.cs).
        if (Environment.GetCommandLineArgs().Contains("--social"))
        {
            SocialShots.Run(outDir);
            return;
        }
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

        // The other pages and overlays, so a restyle is checked on all of them.
        window.TourHideLaunch();
        window.TourGoTo(0);
        Pump(400);
        window.TourShowVersions(-1);
        Pump(500);
        Snap(window, outDir, "10-versions-grid");
        window.TourShowVersions(1);
        Pump(500);
        Snap(window, outDir, "11-versions-detail");
        window.TourHideVersions();
        Pump(300);
        window.TourSetAccountOpen(true);
        Pump(500);
        Snap(window, outDir, "12-accounts");
        window.TourSetAccountOpen(false);
        Pump(300);
        window.TourGoTo(1);
        Pump(800);
        Snap(window, outDir, "13-mods");
        window.TourGoTo(2);
        Pump(800);
        Snap(window, outDir, "14-settings");

        // The smallest window the launcher allows (what 125-150 % scaling leaves on
        // a 1080p screen): the same screens again, to prove nothing clips.
        window.Width = 980;
        window.Height = 600;
        window.TourSetAccountOpen(false);
        window.TourGoTo(0);
        Pump(600);
        Snap(window, outDir, "20-min-home");
        window.TourShowVersions(-1);
        Pump(500);
        Snap(window, outDir, "21-min-versions-grid");
        window.TourShowVersions(1);
        Pump(500);
        Snap(window, outDir, "22-min-versions-detail");
        window.TourHideVersions();
        Pump(300);
        window.TourGoTo(1);
        Pump(800);
        Snap(window, outDir, "23-min-mods");
        window.TourGoTo(2);
        Pump(800);
        Snap(window, outDir, "24-min-settings");

        // The Profiles page and Home with a profile (profile row + Join under Play), at the smallest
        // window too, the second time with the toasts up: the Play card, Play and Join must all fit
        // under the top bar and above the bottom edge, and the toasts must leave them uncovered.
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        Pump(700);
        Snap(window, outDir, "25-min-profiles");
        window.TourShowHomeWithProfile(demoProfiles.ModProfiles[0]);
        Pump(700);
        Snap(window, outDir, "26-min-home-profile");
        foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
        window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
        Pump(700);
        Snap(window, outDir, "27-min-home-profile-toasts");
        window.TourClearToasts();

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

    /// <summary>
    /// Saves the window's content as a PNG at <paramref name="dpi"/> (96 = 100 %, 120 = 125 %, 144 = 150 %),
    /// then runs <see cref="ClipCheck"/> on what was drawn. For a DPI above 96 the caller first gives the
    /// window's visual tree that DPI (<see cref="SetTreeDpi"/>), so layout rounding and text are laid out
    /// for it exactly as on a scaled monitor; this only rasterizes at the matching pixel density.
    /// </summary>
    internal static void Snap(Window window, string dir, string name, double dpi = 96)
    {
        window.UpdateLayout();
        var root = (FrameworkElement)window.Content;
        var scale = dpi / 96.0;
        var bitmap = new RenderTargetBitmap((int)Math.Round(root.ActualWidth * scale), (int)Math.Round(root.ActualHeight * scale),
            dpi, dpi, PixelFormats.Pbgra32);
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
        using (var file = File.Create(Path.Combine(dir, name + ".png")))
            encoder.Save(file);
        ClipCheck(root, dir, name);
    }

    /// <summary>
    /// Gives the whole window the DPI of a monitor scaled to <paramref name="scale"/> (1.25 = 125 %), the
    /// way per-monitor DPI does when the window moves to such a monitor: WPF lays out in device-independent
    /// pixels, rounds every edge to the device pixel grid of that DPI (UseLayoutRounding) and shapes text
    /// for it. Re-measures everything so the new rounding applies. 1.0 puts it back.
    /// </summary>
    internal static void SetTreeDpi(Window window, double scale)
    {
        VisualTreeHelper.SetRootDpi(window, new DpiScale(scale, scale));
        Invalidate(window);
        window.UpdateLayout();
    }

    private static void Invalidate(DependencyObject d)
    {
        if (d is UIElement e)
        {
            e.InvalidateMeasure();
            e.InvalidateArrange();
            e.InvalidateVisual();
        }
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++) Invalidate(VisualTreeHelper.GetChild(d, i));
    }

    /// <summary>
    /// Finds every visible icon (any Shape: Path, Ellipse, Rectangle) and every piece of text that its
    /// parent gave LESS room than it needs, so WPF cut it off at the edge of its slot (the layout clip).
    /// A picture can hide this (a cut "..." still looks like dots); the numbers can't. One line per hit
    /// goes to clip-errors.txt, which fails the launcher-shots workflow; clip-checks.txt counts what was checked.
    /// </summary>
    internal static void ClipCheck(FrameworkElement root, string dir, string name)
    {
        var checkedCount = 0;
        var hits = new List<string>();
        void Walk(DependencyObject d)
        {
            for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++)
            {
                var child = VisualTreeHelper.GetChild(d, i);
                if (child is UIElement { IsVisible: false }) continue;
                if (child is FrameworkElement fe && fe is System.Windows.Shapes.Shape or TextBlock)
                {
                    checkedCount++;
                    if (System.Windows.Controls.Primitives.LayoutInformation.GetLayoutClip(fe) is { } clip)
                    {
                        var shown = clip.Bounds;
                        var need = fe.RenderSize;
                        if (shown.Width < need.Width - 0.5 || shown.Height < need.Height - 0.5)
                            hits.Add($"{name}: {Describe(fe)} needs {need.Width:0.#}x{need.Height:0.#} but shows {shown.Width:0.#}x{shown.Height:0.#}");
                    }
                }
                Walk(child);
            }
        }
        Walk(root);
        File.AppendAllText(Path.Combine(dir, "clip-checks.txt"), $"{name}: {checkedCount} icons and text checked, {hits.Count} cut off{Environment.NewLine}");
        if (hits.Count > 0) File.AppendAllLines(Path.Combine(dir, "clip-errors.txt"), hits);
    }

    // "Path in Button 'Close friends' (Social.Icon)": enough to find it in the XAML.
    private static string Describe(FrameworkElement fe)
    {
        var what = fe is TextBlock t ? $"TextBlock '{t.Text}'" : fe.GetType().Name + (string.IsNullOrEmpty(fe.Name) ? "" : $" '{fe.Name}'");
        for (var d = VisualTreeHelper.GetParent(fe); d != null; d = VisualTreeHelper.GetParent(d))
        {
            if (d is System.Windows.Controls.Primitives.ButtonBase b)
            {
                var label = System.Windows.Automation.AutomationProperties.GetName(b);
                if (string.IsNullOrEmpty(label)) label = b.Content as string ?? b.Name;
                return $"{what} in {b.GetType().Name} '{label}'";
            }
        }
        return what + (fe.Parent is FrameworkElement p ? $" in {p.GetType().Name} '{p.Name}'" : "");
    }
}
