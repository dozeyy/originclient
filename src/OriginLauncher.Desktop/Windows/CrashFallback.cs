using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;

namespace OriginLauncher.Desktop.Windows;

// The crash windows' last resort (the Windows launcher's CrashFallback): if building the themed view throws (the theme,
// a font or an icon is what broke), the same words and buttons are laid out here with plain controls and literal
// colours - no token, no font asset, no icon lookup - so the player always gets a window. These literals are the only
// colours outside Theme/Tokens.cs, on purpose: they must not depend on the thing that may have failed.
internal static class CrashFallback
{
    private static readonly IBrush Bg = new SolidColorBrush(Color.FromRgb(0x11, 0x11, 0x11));
    private static readonly IBrush Well = new SolidColorBrush(Color.FromRgb(0x1A, 0x1A, 0x1A));
    private static readonly IBrush Edge = new SolidColorBrush(Color.FromRgb(0x3A, 0x3A, 0x3A));
    private static readonly IBrush Ink = new SolidColorBrush(Color.FromRgb(0xF0, 0xF0, 0xF0));
    private static readonly IBrush Dim = new SolidColorBrush(Color.FromRgb(0xB0, 0xB0, 0xB0));
    private static readonly IBrush Muted = new SolidColorBrush(Color.FromRgb(0x80, 0x80, 0x80));
    private static readonly IBrush Primary = new SolidColorBrush(Color.FromRgb(0xE0, 0xE0, 0xE0));
    private static readonly IBrush OnPrimary = new SolidColorBrush(Color.FromRgb(0x11, 0x11, 0x11));

    /// <summary>The plain frame: dark fill, a 1 px edge, 24 padding, the given rows stacked.</summary>
    public static Border Frame(params Control[] rows)
    {
        var stack = new StackPanel();
        foreach (var r in rows) stack.Children.Add(r);
        return new Border
        {
            Background = Bg, BorderBrush = Edge, BorderThickness = new Thickness(1), Padding = new Thickness(24), Child = stack,
        };
    }

    public static TextBlock Heading(string text) => new()
    {
        Text = text, FontSize = 20, FontWeight = FontWeight.Bold, Foreground = Ink, TextWrapping = TextWrapping.Wrap,
    };

    public static TextBlock Body(string text, double top = 8) => new()
    {
        Text = text, FontSize = 13, Foreground = Dim, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, top, 0, 0),
    };

    public static TextBlock Caption(string text, double top = 8) => new()
    {
        Text = text, FontSize = 12, Foreground = Muted, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, top, 0, 0),
    };

    /// <summary>The details well: selectable mono-spaced text in a fixed-height scroller.</summary>
    public static Border Details(string text, double height) => new()
    {
        Background = Well, BorderBrush = Edge, BorderThickness = new Thickness(1), Height = height, Margin = new Thickness(0, 12, 0, 0),
        Child = new ScrollViewer
        {
            Content = new SelectableTextBlock
            {
                Text = text, FontFamily = new FontFamily("monospace"), FontSize = 11, Foreground = Dim,
                TextWrapping = TextWrapping.Wrap, Margin = new Thickness(12, 8),
            },
        },
    };

    public static Button Btn(string label, bool primary, Action onClick)
    {
        var b = new Button
        {
            Content = label, MinWidth = 96, Padding = new Thickness(12, 6), Margin = new Thickness(0, 0, 8, 0),
            Background = primary ? Primary : Well, Foreground = primary ? OnPrimary : Ink, BorderBrush = Edge,
            BorderThickness = new Thickness(1),
        };
        b.Click += (_, _) => onClick();
        return b;
    }

    public static StackPanel ButtonRow(params Control[] buttons)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 20, 0, 0) };
        foreach (var b in buttons) row.Children.Add(b);
        return row;
    }
}

// The crash windows' chrome: chromeless like the launcher (no system title bar), a hairline edge, dragged from
// anywhere that is not a control, Esc closes. The content is a plain control, so render runs can draw it inside the
// shot window instead of opening a second one.
internal static class CrashChrome
{
    public static void Dress(Window window, Control content, double width, double? height)
    {
        window.WindowDecorations = WindowDecorations.None;
        window.CanResize = false;
        window.ShowInTaskbar = true;
        window.WindowStartupLocation = WindowStartupLocation.CenterOwner;
        window.Width = width;
        if (height is { } h) window.Height = h;
        else window.SizeToContent = SizeToContent.Height;
        window.Content = content;
        window.PointerPressed += (_, e) =>
        {
            if (e.Source is Control c && IsInsideControl(c)) return;
            if (e.GetCurrentPoint(window).Properties.IsLeftButtonPressed) window.BeginMoveDrag(e);
        };
        window.KeyDown += (_, e) =>
        {
            if (e.Key != Key.Escape || e.Handled) return;
            e.Handled = true;
            window.Close();
        };
    }

    // A press on a button or on selectable text is that control's, not a drag.
    private static bool IsInsideControl(Control c)
    {
        for (Control? v = c; v != null; v = v.Parent as Control)
            if (v is Button or SelectableTextBlock or TextBox or ScrollBar or Kit.Pressable) return true;
        return false;
    }
}
