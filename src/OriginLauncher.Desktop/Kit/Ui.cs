using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

// Small builders for the launcher's views. Every text style is one of section 3's roles; every
// button is a Pressable with a Look. Views compose these instead of setting sizes and colours by hand.
public static class Ui
{
    // ---- Text roles (section 3) ----

    public static TextBlock Text(string text, double size, FontWeight weight, IBrush? brush = null, double tracking = 0)
    {
        var t = new TextBlock
        {
            Text = text,
            FontFamily = Assets.Inter,
            FontSize = size,
            FontWeight = weight,
            LetterSpacing = TypeScale.Track(tracking, size),
            VerticalAlignment = VerticalAlignment.Center,
        };
        if (brush != null) t.Foreground = brush;
        return t;
    }

    /// <summary>type.label: 11 / 600, +0.10 em, UPPER (nav, eyebrows, badges, section labels).</summary>
    public static TextBlock Eyebrow(string text, IBrush? brush = null, double tracking = TypeScale.LabelTracking) =>
        Text(text.ToUpperInvariant(), TypeScale.Label, FontWeight.SemiBold, brush ?? Palette.TextMuted, tracking);

    public static TextBlock Caption(string text, IBrush? brush = null) =>
        Text(text, TypeScale.Caption, FontWeight.Medium, brush ?? Palette.TextDim);

    public static TextBlock Body(string text, IBrush? brush = null) =>
        Wrap(Text(text, TypeScale.Body, FontWeight.Normal, brush ?? Palette.TextDim));

    public static TextBlock BodyStrong(string text, IBrush? brush = null) =>
        Text(text, TypeScale.Body, FontWeight.SemiBold, brush);

    public static TextBlock Title(string text, IBrush? brush = null) =>
        Text(text, TypeScale.Title, FontWeight.SemiBold, brush ?? Palette.Text, -0.005);

    public static TextBlock Heading(string text, IBrush? brush = null) =>
        Text(text, TypeScale.Heading, FontWeight.Bold, brush ?? Palette.Text, -0.015);

    public static TextBlock Display(string text, IBrush? brush = null) =>
        Text(text, TypeScale.Display, FontWeight.ExtraBold, brush ?? Palette.Text, -0.03);

    public static TextBlock Mono(string text, IBrush? brush = null) => new()
    {
        Text = text, FontFamily = Assets.Mono, FontSize = TypeScale.Mono, Foreground = brush ?? Palette.Text,
        TextWrapping = TextWrapping.Wrap,
    };

    public static TextBlock Wrap(TextBlock t)
    {
        t.TextWrapping = TextWrapping.Wrap;
        t.VerticalAlignment = VerticalAlignment.Top;
        return t;
    }

    public static TextBlock Ellipsis(TextBlock t, double maxWidth = double.PositiveInfinity)
    {
        t.TextTrimming = TextTrimming.CharacterEllipsis;
        t.MaxWidth = maxWidth;
        return t;
    }

    /// <summary>The label inside a button of a look: bodyStrong (12 / 600 under 32 tall is set by the caller).</summary>
    public static TextBlock Label(string text, Look look) => Text(text, TypeScale.Body, FontWeight.SemiBold);

    // ---- Icons ----

    public static IconView Icon(string glyph, double size = Sizes.Icon, IBrush? brush = null, bool filled = false) =>
        new() { Glyph = glyph, Size = size, Brush = brush, Filled = filled, VerticalAlignment = VerticalAlignment.Center };

    // ---- Layout ----

    public static StackPanel Row(double gap, params Control[] children)
    {
        var s = new StackPanel { Orientation = Orientation.Horizontal, Spacing = gap, VerticalAlignment = VerticalAlignment.Center };
        s.Children.AddRange(children);
        return s;
    }

    public static StackPanel Column(double gap, params Control[] children)
    {
        var s = new StackPanel { Orientation = Orientation.Vertical, Spacing = gap };
        s.Children.AddRange(children);
        return s;
    }

    public static T At<T>(this T c, int column, int row = 0) where T : Control
    {
        Grid.SetColumn(c, column);
        Grid.SetRow(c, row);
        return c;
    }

    public static T Margin<T>(this T c, double l, double t, double r, double b) where T : Control
    {
        c.Margin = new Thickness(l, t, r, b);
        return c;
    }

    public static T Align<T>(this T c, HorizontalAlignment h, VerticalAlignment v = VerticalAlignment.Center) where T : Control
    {
        c.HorizontalAlignment = h;
        c.VerticalAlignment = v;
        return c;
    }

    public static T Named<T>(this T c, string accessibleName) where T : Control
    {
        AutomationProperties.SetName(c, accessibleName);
        return c;
    }

    public static T Tip<T>(this T c, string tip) where T : Control
    {
        ToolTip.SetTip(c, tip);
        AutomationProperties.SetName(c, AutomationProperties.GetName(c) ?? tip);
        return c;
    }

    // ---- Buttons (section 11.1) ----

    /// <summary>A labelled button: lg 36 in forms and dialogs (pad 14), md 32 in toolbars (pad 12), sm 28 (pad 10).</summary>
    public static Pressable Button(string label, Look look, string? glyph = null, double height = Sizes.ControlLg)
    {
        var pad = height >= Sizes.ControlLg ? 14 : height >= Sizes.ControlMd ? 12 : 10;
        var text = Text(label, height < Sizes.ControlMd ? TypeScale.ControlSm : TypeScale.Body, FontWeight.SemiBold);
        Control content = glyph == null
            ? text
            : Row(height < Sizes.ControlMd ? 6 : 8, Icon(glyph, height < Sizes.ControlMd ? Sizes.IconSm : Sizes.Icon), text);
        return new Pressable(look, content, label)
        {
            Height = height,
            Padding = new Thickness(pad, 0),
            CornerRadius = new CornerRadius(height >= Sizes.ControlLg ? Radius.Md : Radius.Sm),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
        }.Center();
    }

    /// <summary>A square icon button (28 / 32 / 36 / 40): ghost, glyph only, tooltip + accessible name required.</summary>
    public static Pressable IconButton(string glyph, string name, double size = Sizes.ControlMd, Look look = Look.Ghost, IBrush? glyphBrush = null)
    {
        var b = new Pressable(look, Icon(glyph, size <= Sizes.ControlSm ? Sizes.IconSm : Sizes.Icon, glyphBrush), name)
        {
            Width = size,
            Height = size,
            CornerRadius = new CornerRadius(size >= Sizes.ControlLg ? Radius.Md : Radius.Sm),
            VerticalAlignment = VerticalAlignment.Center,
        };
        ToolTip.SetTip(b, name);
        return b;
    }

    public static Pressable Center(this Pressable p)
    {
        if (p.Child is Control c)
        {
            c.HorizontalAlignment = HorizontalAlignment.Center;
            c.VerticalAlignment = VerticalAlignment.Center;
        }
        return p;
    }

    // ---- Shapes ----

    /// <summary>A status dot (6 px default), always a circle.</summary>
    public static Ellipse Dot(IBrush brush, double size = Sizes.Dot) => new()
    {
        Width = size, Height = size, Fill = brush, VerticalAlignment = VerticalAlignment.Center,
    };

    /// <summary>A badge (11.9): 20 tall capsule, pad X 8, caption 500.</summary>
    public static Border Badge(string text, IBrush? fill = null, IBrush? ink = null, IBrush? dot = null)
    {
        var label = Text(text, TypeScale.Caption, FontWeight.Medium, ink ?? Palette.TextDim);
        Control content = dot == null ? label : Row(6, Dot(dot), label);
        return Capsule(new Border
        {
            Height = Sizes.Badge,
            Padding = new Thickness(8, 0),
            Background = fill ?? Palette.GhostHover,
            Child = content,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Left,
        });
    }

    /// <summary>Apple capsule: radius = half the short side, kept in step with the size (never an oval).</summary>
    public static T Capsule<T>(T border) where T : Border
    {
        void Fit() => border.CornerRadius = new CornerRadius(Math.Min(border.Bounds.Width, border.Bounds.Height) / 2);
        if (!double.IsNaN(border.Height)) border.CornerRadius = new CornerRadius(border.Height / 2);
        border.SizeChanged += (_, _) => Fit();
        return border;
    }

    public static Border Divider(bool vertical = false, double length = double.NaN) => vertical
        ? new Border { Width = 1, Height = double.IsNaN(length) ? 20 : length, Background = Palette.LineStrong, VerticalAlignment = VerticalAlignment.Center }
        : new Border { Height = 1, Background = Palette.Line };

    /// <summary>A card: surfaceRaised, 1 px line, radius lg (12), padding 16.</summary>
    public static Border Card(Control child, double pad = Space.S4, double radius = Radius.Lg) => new()
    {
        Background = Palette.Card,
        BorderBrush = Palette.Line,
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(radius),
        Padding = new Thickness(pad),
        Child = child,
    };

    /// <summary>A hue tile (2.3): 28 x 28, radius sm, hue at 16 %, glyph at 100 %. Only in labelled list rows.</summary>
    public static Border Tile(string glyph, IBrush tile, IBrush ink, double size = 28) => new()
    {
        Width = size, Height = size, CornerRadius = new CornerRadius(Radius.Sm), Background = tile,
        Child = Icon(glyph, Sizes.Icon, ink), VerticalAlignment = VerticalAlignment.Center,
    };

    /// <summary>An image that draws nearest-neighbour (player heads, pixel art).</summary>
    public static Image Pixel(IImage? source, double size)
    {
        var i = new Image { Source = source, Width = size, Height = size, Stretch = Stretch.Fill };
        RenderOptions.SetBitmapInterpolationMode(i, Avalonia.Media.Imaging.BitmapInterpolationMode.None);
        return i;
    }

    /// <summary>The Origin logo file, shown as an image (never redrawn).</summary>
    public static Image Logo(double size)
    {
        var i = new Image { Source = Assets.Logo, Width = size, Height = size, Stretch = Stretch.Uniform };
        RenderOptions.SetBitmapInterpolationMode(i, Avalonia.Media.Imaging.BitmapInterpolationMode.HighQuality);
        return i;
    }

    public static Image Wordmark(double height)
    {
        var i = new Image { Source = Assets.Wordmark, Height = height, Stretch = Stretch.Uniform };
        RenderOptions.SetBitmapInterpolationMode(i, Avalonia.Media.Imaging.BitmapInterpolationMode.HighQuality);
        return i;
    }
}
