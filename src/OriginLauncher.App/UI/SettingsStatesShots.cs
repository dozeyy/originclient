using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

/// <summary>
/// CI screen render (RenderShots, Phase 3 U3.7b): every Settings section on its own, the text-field states
/// (focus, error, disabled), an open dropdown, a confirm dialog and the toasts. The popups (a dropdown's list)
/// live in their own window, which a picture of the main window does not contain, so the open list is also saved
/// as its own picture (the "-popup" file) with the window colour behind it, shadow included.
/// A step that throws is written to settings-states-error.txt and the run goes on; the launcher-shots job reads that file.
/// </summary>
internal static class SettingsStatesShots
{
    // The sections not already shot by name in RenderShots (Origin Client and Startup have the 14f/14g click shots).
    private static readonly (string Section, string Name)[] Sections =
    [
        ("SecSync", "14i-settings-sec-sync"),
        ("SecPerformance", "14j-settings-sec-performance"),
        ("SecGame", "14k-settings-sec-game"),
        ("SecJvm", "14l-settings-sec-jvm"),
        ("SecDeveloper", "14m-settings-sec-developer"),
    ];

    internal static void Run(MainWindow window, string dir)
    {
        window.TourGoTo(2);
        RenderShots.Pump(700);
        var page = FindVisual<Pages.SettingsPage>(window);
        if (page == null)
        {
            Log(dir, "settings page not found");
            return;
        }

        foreach (var (section, name) in Sections)
            Step(dir, name, () =>
            {
                page.TourClickRail(section);
                RenderShots.Pump(500);
                RenderShots.Snap(window, dir, name);
            });

        Step(dir, "jvm-undo", () => JvmUndo(window, page, dir));
        Step(dir, "inputs", () => Inputs(window, page, dir));
        Step(dir, "dropdown", () => Dropdown(window, page, dir));
        Step(dir, "confirm", () => Confirms(window, dir));
        Step(dir, "toasts", () => Toasts(window, dir));

        page.ShowFromTop();
        RenderShots.Pump(200);
    }

    /// <summary>
    /// Java arguments after the crash window reset them: the "Reset after a crash" row with Undo. The record is put in
    /// the scratch settings through the real repair (RepairExecutor.ApplySettings), then removed again. Never runs
    /// outside the scratch root (it writes settings).
    /// </summary>
    private static void JvmUndo(MainWindow window, Pages.SettingsPage page, string dir)
    {
        if (!Core.OriginPaths.IsScratchRoot) return;
        var version = page.FindName("JvmVersionComboBox") is ComboBox { SelectedItem: string v } ? v : null;
        if (version == null) return;
        Core.SettingsStore.Update(s => s.JvmArgsByVersion[version] =
            new Core.Models.VersionJvmOptions { Arguments = "-XX:+UseZGCx -Xss2m" });
        Core.Compat.RepairExecutor.ApplySettings(Core.Compat.RepairPlanner.ResetJavaArgs(version,
            Core.SettingsStore.Load().JvmArgsFor(version), "Java refused to start the game with them."), Core.SettingsStore.Update);
        page.TourReloadJvm();
        page.TourClickRail("SecJvm");
        RenderShots.Pump(500);
        RenderShots.Snap(window, dir, "14lb-settings-jvm-undo");
        Core.SettingsStore.Update(s => s.JvmArgsBeforeReset.Remove(version));
        page.TourReloadJvm();
        RenderShots.Pump(200);
    }

    /// <summary>Text fields: keyboard focus, the disabled state, then the two error states (a bad folder, a too-small width).</summary>
    private static void Inputs(MainWindow window, Pages.SettingsPage page, string dir)
    {
        var path = (TextBox)page.FindName("InstallPathTextBox");
        var width = (TextBox)page.FindName("ResolutionWidthTextBox");
        page.TourClickRail("SecGame");
        RenderShots.Pump(500);

        path.BringIntoView();
        RenderShots.Pump(200);
        path.Focus();
        RenderShots.Pump(350);
        RenderShots.Snap(window, dir, "14n-input-focus", adorners: true);

        window.Focus();
        width.IsEnabled = false;
        RenderShots.Pump(300);
        RenderShots.Snap(window, dir, "14o-input-disabled");
        width.IsEnabled = true;

        page.TourShowFieldErrors();
        RenderShots.Pump(350);
        RenderShots.Snap(window, dir, "14p-input-error");
        page.TourClearFieldErrors();
        RenderShots.Pump(200);
    }

    /// <summary>An open dropdown: the window picture, and the list on its own (it is a popup window).</summary>
    private static void Dropdown(MainWindow window, Pages.SettingsPage page, string dir)
    {
        var combo = (ComboBox)page.FindName("JvmVersionComboBox");
        page.TourClickRail("SecJvm");
        RenderShots.Pump(500);
        // A fresh CI profile has no installed versions: give the list something to show. Adding items selects nothing,
        // so no setting is written.
        var added = combo.Items.Count == 0 && combo.ItemsSource == null;
        if (added)
            foreach (var v in new[] { "1.21.1", "1.20.4", "1.19.4" }) combo.Items.Add(new ComboBoxItem { Content = v });
        try
        {
            combo.IsDropDownOpen = true;
            RenderShots.Pump(500);
            RenderShots.Snap(window, dir, "14q-dropdown-open");
            if (combo.Template.FindName("Popup", combo) is Popup { Child: FrameworkElement list })
                SnapPopup(window, list, dir, "14q-dropdown-open-popup");
        }
        finally
        {
            combo.IsDropDownOpen = false;
            if (added) combo.Items.Clear();
            RenderShots.Pump(200);
        }
    }

    /// <summary>The confirm dialog, short and with a long title and message (it must wrap, not clip).</summary>
    private static void Confirms(MainWindow window, string dir)
    {
        var root = (Panel)window.Content;
        var confirm = new ConfirmOverlay { Visibility = Visibility.Collapsed };
        Panel.SetZIndex(confirm, int.MaxValue);
        Grid.SetRowSpan(confirm, 99);
        Grid.SetColumnSpan(confirm, 99);
        root.Children.Add(confirm);
        try
        {
            _ = confirm.ShowAsync("Remove friend?", "Steve will no longer see when you are online.", "Remove", "Cancel", destructive: true);
            RenderShots.Pump(500);
            RenderShots.Snap(window, dir, "14r-confirm");
            confirm.Dismiss();

            _ = confirm.ShowAsync("Switch to a version that is not installed on this computer yet?",
                "Origin will download the game files for Minecraft 1.21.1, then restart the launch. This can take a few minutes on a slow connection, and you can keep using the launcher while it runs.",
                "Download and switch", "Not now");
            RenderShots.Pump(500);
            RenderShots.Snap(window, dir, "14s-confirm-long");
            confirm.Dismiss();
        }
        finally
        {
            confirm.Dismiss();
            root.Children.Remove(confirm);
            RenderShots.Pump(200);
        }
    }

    /// <summary>Toasts over Settings: a short one with an action, a long one that wraps, and the "+N more" row.</summary>
    private static void Toasts(MainWindow window, string dir)
    {
        window.TourClearToasts();
        try
        {
            window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
            window.TourShowToast(new SocialToast(SocialToastKind.Notice, "Could not join Kai_Builds",
                "That party is on a Minecraft version you do not have installed, so Origin cannot start it from here until you install it."));
            RenderShots.Pump(600);
            RenderShots.Snap(window, dir, "14t-toasts");
            window.TourShowToast(new SocialToast(SocialToastKind.PartyInvite, "Party invite", "Kai_Builds invited you", Uuid: SocialDemo.Kai, PartyId: "p2"));
            RenderShots.Pump(600);
            RenderShots.Snap(window, dir, "14u-toasts-more");
        }
        finally
        {
            window.TourClearToasts();
            RenderShots.Pump(200);
        }
    }

    /// <summary>A popup's content saved at 2x over the window colour (its edges and shadow are transparent).</summary>
    private static void SnapPopup(Window window, FrameworkElement element, string dir, string name)
    {
        // The shadow is drawn into the margin around the popup's content (Popup.Bleed), outside the element's own
        // bounds, so the picture is cut from a rectangle that reaches past it on every side.
        const double pad = 24;
        var w = Math.Max(1, (int)Math.Ceiling(element.ActualWidth + 2 * pad));
        var h = Math.Max(1, (int)Math.Ceiling(element.ActualHeight + 2 * pad));
        var bitmap = new RenderTargetBitmap(w * 2, h * 2, 192, 192, PixelFormats.Pbgra32);
        var look = new VisualBrush(element)
        {
            Stretch = Stretch.None,
            AlignmentX = AlignmentX.Left,
            AlignmentY = AlignmentY.Top,
            ViewboxUnits = BrushMappingMode.Absolute,
            Viewbox = new Rect(-pad, -pad, w, h),
            ViewportUnits = BrushMappingMode.Absolute,
            Viewport = new Rect(0, 0, w, h),
        };
        var back = new DrawingVisual();
        using (var dc = back.RenderOpen())
        {
            dc.DrawRectangle(window.Background, null, new Rect(0, 0, w, h));
            dc.DrawRectangle(look, null, new Rect(0, 0, w, h));
        }
        bitmap.Render(back);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }

    private static void Step(string dir, string what, Action body)
    {
        try
        {
            body();
        }
        catch (Exception ex)
        {
            Log(dir, $"{what}: {ex}");
        }
    }

    private static void Log(string dir, string text) =>
        File.AppendAllText(Path.Combine(dir, "settings-states-error.txt"), text + Environment.NewLine);

    private static T? FindVisual<T>(DependencyObject parent) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T t) return t;
            if (FindVisual<T>(child) is { } found) return found;
        }
        return null;
    }
}
