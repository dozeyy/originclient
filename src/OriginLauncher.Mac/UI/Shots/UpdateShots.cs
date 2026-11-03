using System.Text;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Headless;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.VisualTree;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.Mac.UI.Shots;

/// <summary>
/// Render mode only (called from RenderShots): the self-update chip and the capsule scroll bar.
///  - Twins of every Windows update shot, under the SAME names (WPF RenderShots 15b/15d/15e-*-update, AccountShots 16l):
///    the chip in its ready state at each width the Windows run pictures, the fit rule's numbers in topbar-fit.txt.
///  - Mac extras (15u-*): the chip's other states (Updating..., Update failed - retry, Move to Applications at both widths,
///    the move-to-Applications notice, hover) and Home's update scene mid-download.
///  - 15v-*: the scroll bar (Theme/Inputs.axaml ScrollBar.Minimal) at rest, on hover and scrolled, with its measured thumb in
///    scrollbar-fit.txt (8 wide, a 4 wide capsule thumb). The Windows look to compare with: any WPF list with
///    ScrollBar.Minimal (the import lists, the version picker).
/// Every state is set through MainWindow.TourShowUpdate / Home's own scene calls; nothing goes online.
/// </summary>
internal static class UpdateShots
{
    private const string Widest = "WWWWWWWWWWWWWWWW"; // AreaBShots / WPF AccountShots: the widest 16-letter name

    internal static void Run(MainWindow window, string outDir)
    {
        try
        {
            Chip(window, outDir);
            ScrollBars(window, outDir);
        }
        catch (Exception e)
        {
            RenderShots.Fail($"update shots ({e.GetType().Name}: {e.Message})");
            File.WriteAllText(Path.Combine(outDir, "update-shots-error.txt"), e.ToString());
        }
        finally
        {
            window.TourShowUpdate(MainWindow.TourUpdate.Hidden);
            window.TourShowAccount(null, "", false);
            window.TourClearToasts();
            RenderShots.Resize(window, 1180, 740);
        }
    }

    private static void Shot(MainWindow window, string name)
    {
        RenderShots.PumpFor(350);
        RenderShots.Snap(window, name);
    }

    private static void Chip(MainWindow window, string outDir)
    {
        var fit = new StringBuilder();
        window.TourGoTo(MainWindow.LauncherPage.Home);
        window.TourShowAccount(null, "", false);

        // 15e: the fit rule at the three wide sizes, with the chip (the Windows run's -update twins).
        window.TourShowUpdate(MainWindow.TourUpdate.Ready);
        foreach (var (w, h) in new[] { (1440, 900), (1280, 800), (1180, 740) })
        {
            RenderShots.Resize(window, w, h);
            var shot = $"15e-topbar-{w}x{h}-update";
            RenderShots.Pump();
            fit.AppendLine($"{shot}: {window.TopBarFit()} {ChipFit(window)}");
            Shot(window, shot);
        }

        // 15 / 15b / 15c / 15d: the Windows CI desktop's 1044 wide window and the narrowest, without and with the chip.
        foreach (var (w, h, plain, update) in new[] { (1044, 740, "15-topbar-1044", "15b-topbar-1044-update"), (960, 600, "15c-topbar-960", "15d-topbar-960-update") })
        {
            RenderShots.Resize(window, w, h);
            window.TourShowUpdate(MainWindow.TourUpdate.Hidden);
            RenderShots.Pump();
            fit.AppendLine($"{plain}: {window.TopBarFit()}");
            Shot(window, plain);
            window.TourShowUpdate(MainWindow.TourUpdate.Ready);
            RenderShots.Pump();
            fit.AppendLine($"{update}: {window.TopBarFit()} {ChipFit(window)}");
            Shot(window, update);
        }

        // 16l: the narrowest window with the longest name and the chip (WPF AccountShots).
        window.TourShowAccount(Widest, SocialDemo.Steve, false);
        RenderShots.Pump();
        fit.AppendLine($"16l-bar-960-long-name-update: {window.TopBarFit()} {ChipFit(window)} {window.TourAccountFit()}");
        Shot(window, "16l-bar-960-long-name-update");
        window.TourShowAccount(null, "", false);

        // The chip's other states, wide and compact.
        foreach (var (w, h, tag) in new[] { (1180, 740, "1180"), (960, 600, "960") })
        {
            RenderShots.Resize(window, w, h);
            foreach (var (state, name) in new[]
                     {
                         (MainWindow.TourUpdate.Updating, "updating"),
                         (MainWindow.TourUpdate.Failed, "failed-retry"),
                         (MainWindow.TourUpdate.Blocked, "move-to-applications"),
                     })
            {
                window.TourShowUpdate(state);
                RenderShots.Pump();
                var shot = $"15u-chip-{name}-{tag}";
                fit.AppendLine($"{shot}: {window.TopBarFit()} {ChipFit(window)}");
                Shot(window, shot);
            }
        }

        // Hover on the ready chip (the gold wash lifts), at 1180.
        RenderShots.Resize(window, 1180, 740);
        window.TourShowUpdate(MainWindow.TourUpdate.Ready);
        RenderShots.Pump();
        var chip = window.TourUpdateBadge;
        var centre = chip.TranslatePoint(new Point(chip.Bounds.Width / 2, chip.Bounds.Height / 2), window) ?? default;
        window.MouseMove(centre);
        Shot(window, "15u-chip-ready-hover-1180");
        window.MouseMove(new Point(4, 400));

        // What a click on "Move to Applications" says: the notice with the reason UpdateService gives on a translocated app.
        window.TourShowUpdate(MainWindow.TourUpdate.Blocked);
        window.TourShowToast(new SocialToast(SocialToastKind.Notice, "Move Origin to Applications",
            "Origin Launcher is running from a temporary copy macOS made. Move it to Applications, open it from there, and the update installs."));
        Shot(window, "15u-move-to-applications-notice");
        window.TourClearToasts();

        // Home's update scene mid-download (a click on the ready chip): the real percentage fill, no Cancel.
        window.TourShowUpdate(MainWindow.TourUpdate.Ready);
        if (window.Home.BeginUpdateScene("1.0.80"))
        {
            window.Home.ReportUpdate(new UpdateService.UpdateProgress(UpdateService.UpdateStage.Downloading, 53L * 1_048_576, 126L * 1_048_576));
            Shot(window, "15u-update-scene-downloading");
            window.Home.ReportUpdate(new UpdateService.UpdateProgress(UpdateService.UpdateStage.Installing, 0, null));
            Shot(window, "15u-update-scene-installing");
            window.Home.EndUpdateScene();
        }
        else RenderShots.Fail("15u-update-scene (Home refused the update scene)");
        RenderShots.Pump();

        File.WriteAllText(Path.Combine(outDir, "update-fit.txt"), fit.ToString());
    }

    // The chip's size and form, so the compact rule (below 1100 the icon button 32) is a number, not an eyeball.
    private static string ChipFit(MainWindow window)
    {
        var chip = window.TourUpdateBadge;
        var theme = chip.Theme == (ControlTheme?)window.FindResource("Button.UpdateChip") ? "UpdateChip" : "Chrome";
        return $"chip={chip.Bounds.Width:0}x{chip.Bounds.Height:0} theme={theme} visible={chip.IsVisible}";
    }

    // A card with a long list over Home: the bar every Mac ScrollViewer now wears (App.axaml), at rest, hovered, scrolled.
    private static void ScrollBars(MainWindow window, string outDir)
    {
        RenderShots.Resize(window, 1180, 740);
        window.TourGoTo(MainWindow.LauncherPage.Home);
        var list = new StackPanel { Margin = new Thickness(16, 12) };
        for (var i = 1; i <= 40; i++)
            list.Children.Add(new TextBlock
            {
                Text = $"Row {i} - a list long enough to scroll",
                Theme = (ControlTheme?)window.FindResource("Text.Body"),
                Margin = new Thickness(0, 4),
            });
        var scroller = new ScrollViewer
        {
            Content = list,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
        };
        var card = new Border
        {
            Width = 360, Height = 280,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
            Background = (IBrush?)window.FindResource("Brush.Card"),
            BorderBrush = (IBrush?)window.FindResource("Brush.Stroke"),
            BorderThickness = new Thickness(1),
            CornerRadius = (CornerRadius)window.FindResource("Radius.Md")!,
            Padding = new Thickness(0, 4, 4, 4),
            Child = scroller,
        };
        var host = (Panel)window.Content!;
        host.Children.Add(card);
        try
        {
            RenderShots.Pump();
            var log = new StringBuilder();
            log.AppendLine($"15v-scrollbar-rest: {ThumbFit(scroller)}");
            Shot(window, "15v-scrollbar-rest");

            var thumb = scroller.GetVisualDescendants().OfType<Avalonia.Controls.Primitives.Thumb>().FirstOrDefault();
            if (thumb != null)
            {
                var c = thumb.TranslatePoint(new Point(thumb.Bounds.Width / 2, thumb.Bounds.Height / 2), window) ?? default;
                window.MouseMove(c);
                log.AppendLine($"15v-scrollbar-hover: {ThumbFit(scroller)}");
                Shot(window, "15v-scrollbar-hover");
                window.MouseMove(new Point(4, 400));
            }
            else RenderShots.Fail("15v-scrollbar-hover (no thumb)");

            scroller.Offset = new Vector(0, (scroller.Extent.Height - scroller.Viewport.Height) / 2);
            RenderShots.Pump();
            log.AppendLine($"15v-scrollbar-scrolled: {ThumbFit(scroller)}");
            Shot(window, "15v-scrollbar-scrolled");
            File.WriteAllText(Path.Combine(outDir, "scrollbar-fit.txt"), log.ToString());
        }
        finally
        {
            host.Children.Remove(card);
            RenderShots.Pump();
        }
    }

    // The bar's width, its theme and the thumb's ink: the capsule is 8 - 2 x 2 = 4 wide with radius 2.
    private static string ThumbFit(ScrollViewer scroller)
    {
        var bar = scroller.GetVisualDescendants().OfType<Avalonia.Controls.Primitives.ScrollBar>()
            .FirstOrDefault(b => b.Orientation == Orientation.Vertical);
        if (bar == null) return "no vertical scroll bar";
        var ink = bar.GetVisualDescendants().OfType<Border>().FirstOrDefault(b => b.Name == "Bar");
        var brush = ink?.Background is ISolidColorBrush s ? s.Color.ToString() : "?";
        return $"bar={bar.Bounds.Width:0.#}x{bar.Bounds.Height:0.#} visible={bar.IsVisible} "
            + $"thumb={ink?.Bounds.Width:0.#}x{ink?.Bounds.Height:0.#} radius={ink?.CornerRadius.TopLeft:0.#} colour={brush}";
    }
}
