using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI-only: `OriginLauncher.App.exe --render-shots &lt;folder&gt;` opens the real
/// main window with made-up social data (Core/Social/SocialDemo) and saves
/// pictures of Home and the Friends drawer, so launcher UI changes are looked
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

        window.Close();
    }

    /// <summary>Runs the dispatcher for a while so layout, bindings and animations settle.</summary>
    private static void Pump(int milliseconds)
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

    private static void Snap(Window window, string dir, string name)
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
