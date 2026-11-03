using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

// Pieces the Mods and Profiles pages share (the WPF pages share them through styles): the page column, the
// status strip, the version chip and the hover-lit surface every row and card uses.
public static class ModsParts
{
    /// <summary>
    /// The page column (WPF Page.Column): at most 1000 wide, left-aligned, 32 in from the sides and the bottom, in a
    /// scroller whose bar stays hidden (the wheel still scrolls). The top inset is 48 under the bar, 32 when the
    /// window is under 680 tall, so the smallest window keeps its content.
    /// </summary>
    public static ScrollViewer Column(Panel page, Control content, out Border column)
    {
        var col = new Border
        {
            MaxWidth = Sizes.PageMaxWidth,
            HorizontalAlignment = HorizontalAlignment.Left,
            Margin = new Thickness(Space.S8, Space.S12, Space.S8, Space.S8),
            Child = content,
        };
        column = col;
        page.SizeChanged += (_, e) =>
        {
            var top = e.NewSize.Height + Sizes.Bar < 680 ? Space.S8 : Space.S12;
            if (col.Margin.Top != top) col.Margin = new Thickness(Space.S8, top, Space.S8, Space.S8);
        };
        // The column's width follows the page (MaxWidth caps it), so text wraps instead of growing the scroller.
        page.SizeChanged += (_, e) => col.Width = Math.Max(0, e.NewSize.Width - Space.S8 * 2);
        return new ScrollViewer
        {
            Content = col,
            VerticalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Hidden,
            HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
        };
    }

    /// <summary>A small label on CardAlt, a capsule (the Mods version tag, the profile card's version).</summary>
    public static Border Chip(string text, out TextBlock label)
    {
        label = Ui.Text(text, TypeScale.Caption, FontWeight.SemiBold, Palette.Text);
        return Ui.Capsule(new Border
        {
            Background = Palette.CardHover,
            Padding = new Thickness(6, 3),
            Child = label,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Left,
        });
    }

    /// <summary>A surface that lifts from Panel to Card while the pointer is over it (WPF Surface.Card / Surface.Row).
    /// The 1 px edge is clear at rest; a caller sets it for the confirm (danger) and replace (accent) rows.</summary>
    public static Border HoverSurface(double radius, Thickness padding)
    {
        var b = new Border
        {
            Background = Palette.Panel,
            BorderBrush = Brushes.Transparent,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = padding,
        };
        b.PointerEntered += (_, _) => b.Background = Palette.Card;
        b.PointerExited += (_, _) => b.Background = Palette.Panel;
        return b;
    }
}

/// <summary>
/// One strip under a page's header (WPF StatusBox): a failure reads in the danger colour (dot + text on the soft
/// danger fill), a note in the normal text with the accent dot on Card. Hidden when there is nothing to say.
/// </summary>
public sealed class StatusStrip : Border
{
    private readonly Ellipse _dot = Ui.Dot(Palette.Accent);
    private readonly TextBlock _text = Ui.Body("");

    public StatusStrip()
    {
        IsVisible = false;
        Margin = new Thickness(0, 0, 0, Space.S4);
        Padding = new Thickness(Space.S3, Space.S2);
        CornerRadius = new CornerRadius(Radius.Md);
        _dot.VerticalAlignment = VerticalAlignment.Top;
        _dot.Margin = new Thickness(0, 8, 12, 0);
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        grid.Children.Add(_dot.At(0));
        grid.Children.Add(_text.At(1));
        Child = grid;
    }

    public string Text => _text.Text ?? "";
    public bool IsError { get; private set; }

    public void Show(string? message, bool error)
    {
        IsError = error;
        _text.Text = message ?? "Something went wrong.";
        _text.Foreground = error ? Palette.DangerText : Palette.Text;
        _dot.Fill = error ? Palette.Danger : Palette.Accent;
        Background = error ? Palette.DangerSoft : Palette.Card;
        IsVisible = true;
    }

    public void Hide() => IsVisible = false;
}

/// <summary>A dashed outline drawn over a surface (the drop zone, the New profile card): 1 px, "4 4", radius lg.</summary>
public static class Dash
{
    public static Rectangle Outline(IBrush stroke) => new()
    {
        Stroke = stroke,
        StrokeThickness = 1,
        StrokeDashArray = [4, 4],
        RadiusX = Radius.Lg,
        RadiusY = Radius.Lg,
        IsHitTestVisible = false,
    };
}
