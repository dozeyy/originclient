using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Windows;

// The launcher's own error dialog (the Windows launcher's CrashWindow): a plain summary plus a copy-paste-ready
// technical report (exception type, message, stack, environment) so the player can search it or hand it off. If the
// themed view is what broke, the same content is built with literal colours (CrashFallback): there is always a window.
public sealed class CrashWindow : Window
{
    public bool UsingFallbackLayout { get; }

    public CrashWindow(string report, string? savedPath, bool fatal)
    {
        Title = "Origin Launcher";
        Control view;
        try
        {
            view = new CrashView(report, savedPath, fatal, Close, this);
            Background = Palette.Panel;
        }
        catch (Exception)
        {
            UsingFallbackLayout = true;
            view = CrashView.Fallback(report, savedPath, fatal, Close, this);
        }
        CrashChrome.Dress(this, view, Sizes.CrashW, Sizes.CrashH);
    }
}

/// <summary>The launcher error dialog's content; render runs show it inside the shot window.</summary>
public sealed class CrashView : Border
{
    public CrashView(string report, string? savedPath, bool fatal, Action close, Visual? host = null)
    {
        Background = Palette.Panel;
        BorderBrush = Palette.LineStrong;
        BorderThickness = new Thickness(1);
        Padding = new Thickness(Space.S6);

        var tile = new Border
        {
            Width = Sizes.TileLg, Height = Sizes.TileLg, CornerRadius = new CornerRadius(Radius.Md), Background = Palette.DangerSoft,
            Child = Ui.Icon(Icons.Alert, Sizes.IconLg, Palette.DangerText).Align(HorizontalAlignment.Center, VerticalAlignment.Center),
            VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 0, Space.S4, 0),
        };
        var heading = Ui.Wrap(Ui.Heading(Heading(fatal)));
        var summary = Ui.Body(Summary(fatal));
        summary.LineHeight = 20;
        summary.Margin = new Thickness(0, 4, 0, 0);
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        header.Children.Add(tile.At(0));
        header.Children.Add(Ui.Column(0, heading, summary).At(1));

        var eyebrow = Ui.Eyebrow("Technical details", Palette.TextDim);
        eyebrow.Margin = new Thickness(0, Space.S6, 0, 0);
        var details = new Border
        {
            Margin = new Thickness(0, Space.S2, 0, 0), Background = Palette.BgAlt, BorderBrush = Palette.Line,
            BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Lg), ClipToBounds = true,
            Child = new ScrollViewer
            {
                HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Auto,
                Content = new SelectableTextBlock
                {
                    Text = report, FontFamily = Assets.Mono, FontSize = TypeScale.Label, Foreground = Palette.TextDim,
                    TextWrapping = TextWrapping.NoWrap, Margin = new Thickness(Space.S4, Space.S3),
                },
            },
        };
        var saved = Ui.Wrap(Ui.Caption(savedPath == null ? "" : $"A copy of this report was saved to:\n{savedPath}", Palette.TextMuted));
        saved.Margin = new Thickness(0, Space.S2, 0, 0);
        saved.IsVisible = !string.IsNullOrEmpty(savedPath);

        var copy = Ui.Button("Copy details", Look.Secondary, height: Sizes.ControlMd);
        copy.MinWidth = 96;
        var copyLabel = (TextBlock)copy.Child!;
        copy.Click += async (_, _) =>
        {
            try
            {
                var clipboard = TopLevel.GetTopLevel(host ?? this)?.Clipboard ?? throw new InvalidOperationException("no clipboard");
                await clipboard.SetTextAsync(report);
                copyLabel.Text = "Copied!";
            }
            catch
            {
                // The clipboard can fail for a moment (another app holds it): the text stays selectable.
                copyLabel.Text = "Press Ctrl+C";
            }
        };
        var closeButton = Ui.Button(fatal ? "Close" : "Continue", Look.Primary, height: Sizes.ControlMd);
        closeButton.MinWidth = 96;
        closeButton.Click += (_, _) => close();
        var buttons = Ui.Row(Space.S2, copy, closeButton);
        buttons.HorizontalAlignment = HorizontalAlignment.Right;
        buttons.Margin = new Thickness(0, Space.S6, 0, 0);

        var grid = new Grid { RowDefinitions = new RowDefinitions("Auto,Auto,*,Auto,Auto") };
        grid.Children.Add(header.At(0, 0));
        grid.Children.Add(eyebrow.At(0, 1));
        grid.Children.Add(details.At(0, 2));
        grid.Children.Add(saved.At(0, 3));
        grid.Children.Add(buttons.At(0, 4));
        Child = grid;
    }

    internal static string Heading(bool fatal) => fatal ? "Origin Launcher has to close" : "Origin Launcher ran into a problem";

    internal static string Summary(bool fatal) => fatal
        ? "An unexpected error means the launcher can't keep running. The details below explain exactly what happened. Click Copy details, then paste them into a search or send them along so it can be fixed."
        : "Something went wrong, but the launcher recovered and is still open. The details below explain exactly what happened. Click Copy details, then paste them into a search or send them along so it can be fixed.";

    /// <summary>The plain view (CrashFallback), for when the themed one throws.</summary>
    internal static Control Fallback(string report, string? savedPath, bool fatal, Action close, Visual host)
    {
        var rows = new List<Control>
        {
            CrashFallback.Heading(Heading(fatal)), CrashFallback.Body(Summary(fatal)), CrashFallback.Details(report, 300),
        };
        if (!string.IsNullOrEmpty(savedPath)) rows.Add(CrashFallback.Caption($"A copy of this report was saved to:\n{savedPath}"));
        rows.Add(CrashFallback.ButtonRow(
            CrashFallback.Btn("Copy details", primary: false, () =>
            {
                try { _ = TopLevel.GetTopLevel(host)?.Clipboard?.SetTextAsync(report); } catch { }
            }),
            CrashFallback.Btn(fatal ? "Close" : "Continue", primary: true, close)));
        return CrashFallback.Frame(rows.ToArray());
    }
}
