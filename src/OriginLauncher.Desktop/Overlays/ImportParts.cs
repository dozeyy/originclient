using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Controls.Shapes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The Import page's small parts (the twin of the WPF ImportPanel.xaml styles): the tick box, a launcher's logo
// with its soft hue edge, section labels, stat boxes, the four-step row and the quiet note lines. Pure view
// builders: every colour and size is a token (Theme/Tokens.cs), every glyph an Origin icon or the shield the
// WPF page uses, every logo one of the launcher logo files (never redrawn).
internal static class ImportParts
{
    /// <summary>The WPF page's shield glyph (ImportPanel.xaml "Import.Icon.Shield"): the 24 px master, stroked like an icon.</summary>
    private const string ShieldData = "M12,3 L5,6 L5,11 C5,15.5 8,19 12,21 C16,19 19,15.5 19,11 L19,6 Z M9,12 L11,14 L15,10";

    /// <summary>A line with **bold** parts (the numbers): bold runs in the text colour, the rest in <paramref name="brush"/>.</summary>
    public static TextBlock Rich(string text, IBrush? brush = null, double size = TypeScale.Body)
    {
        var block = Ui.Wrap(Ui.Text("", size, FontWeight.Normal, brush ?? Palette.TextDim));
        SetRich(block, text);
        return block;
    }

    public static void SetRich(TextBlock block, string text)
    {
        block.Text = null;
        block.Inlines ??= new InlineCollection();
        block.Inlines.Clear();
        var bold = false;
        foreach (var part in text.Split("**"))
        {
            if (part.Length > 0)
            {
                var run = new Run(part);
                if (bold)
                {
                    run.FontWeight = FontWeight.Bold;
                    run.Foreground = Palette.Text;
                }
                block.Inlines.Add(run);
            }
            bold = !bold;
        }
    }

    /// <summary>The section label over a group ("VERSIONS - 4"; gold for "CAN'T COME OVER" and "LEFT OUT").</summary>
    public static TextBlock Section(string title, int count = 0, bool gold = false)
    {
        var t = Ui.Eyebrow(count > 0 ? $"{title} - {count}" : title, gold ? Palette.Warning : Palette.TextMuted);
        t.Margin = new Thickness(0, 14, 0, 6);
        t.HorizontalAlignment = HorizontalAlignment.Left;
        return t;
    }

    /// <summary>The go chevron at a row's end (Icon.Chevron turned to point right).</summary>
    public static Control Go() => new IconView
    {
        Glyph = "Chevron", Size = 10, Brush = Palette.TextMuted, VerticalAlignment = VerticalAlignment.Center,
        Margin = new Thickness(10, 0, 2, 0), RenderTransform = new RotateTransform(-90),
    };

    public static Control Shield(double size = Sizes.Icon, IBrush? brush = null)
    {
        var geometry = Geometry.Parse(ShieldData);
        return new Avalonia.Controls.Shapes.Path
        {
            Data = geometry, Width = size, Height = size, Stretch = Stretch.None,
            Stroke = brush ?? Palette.TextDim, StrokeThickness = Sizes.IconStroke,
            StrokeLineCap = PenLineCap.Round, StrokeJoin = PenLineJoin.Round,
            RenderTransform = new ScaleTransform(size / 24.0, size / 24.0),
            RenderTransformOrigin = new RelativePoint(0, 0, RelativeUnit.Relative),
            VerticalAlignment = VerticalAlignment.Top,
        };
    }

    /// <summary>A quiet note line with a small icon (the shield, the globe, the alert) in front.</summary>
    public static Control Note(string text, string? glyph, IBrush? brush = null, bool shield = false)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), Margin = new Thickness(0, 6) };
        Control icon = shield ? Shield(Sizes.Icon, Palette.TextDim)
            : Ui.Icon(glyph ?? Icons.Globe, Sizes.Icon, Palette.TextDim).Align(HorizontalAlignment.Left, VerticalAlignment.Top);
        icon.Margin = new Thickness(0, 2, 12, 0);
        grid.Children.Add(icon.At(0));
        var t = Rich(text, brush ?? Palette.TextDim);
        grid.Children.Add(t.At(1));
        return grid;
    }

    /// <summary>A launcher's logo (28 / 32 / 48): its logo file on a dark well with a soft edge in the launcher's hue, or its initial.</summary>
    public static Border Logo(SourceKind kind, double size, string? title = null)
    {
        Control inner;
        if (LogoBitmap(kind) is { } bmp)
        {
            var img = new Image { Source = bmp, Width = size * 0.62, Height = size * 0.62, Stretch = Stretch.Uniform };
            RenderOptions.SetBitmapInterpolationMode(img, Avalonia.Media.Imaging.BitmapInterpolationMode.HighQuality);
            inner = img;
        }
        else
        {
            inner = Ui.Text(ImportLogic.Initial(title ?? ImportFlowModel.LauncherTitle(kind)), size >= 40 ? TypeScale.Title : TypeScale.Caption,
                FontWeight.SemiBold, Palette.TextDim);
        }
        inner.HorizontalAlignment = HorizontalAlignment.Center;
        inner.VerticalAlignment = VerticalAlignment.Center;
        return new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(size >= 40 ? Radius.Lg : Radius.Md),
            Background = Palette.Bg, BorderBrush = Edge(kind), BorderThickness = new Thickness(1),
            Child = inner, VerticalAlignment = VerticalAlignment.Center,
        };
    }

    /// <summary>The small round well a chip or a missing launcher wears (the logo at 18, or the initial).</summary>
    public static Border RoundLogo(SourceKind kind, double size, string? title = null)
    {
        Control inner;
        if (LogoBitmap(kind) is { } bmp)
        {
            var img = new Image { Source = bmp, Width = size * 0.64, Height = size * 0.64, Stretch = Stretch.Uniform };
            RenderOptions.SetBitmapInterpolationMode(img, Avalonia.Media.Imaging.BitmapInterpolationMode.HighQuality);
            inner = img;
        }
        else
        {
            inner = Ui.Text(ImportLogic.Initial(title ?? ImportFlowModel.LauncherTitle(kind)), TypeScale.Caption, FontWeight.SemiBold, Palette.TextDim);
        }
        inner.HorizontalAlignment = HorizontalAlignment.Center;
        inner.VerticalAlignment = VerticalAlignment.Center;
        return new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = Palette.Bg,
            BorderBrush = Palette.Line, BorderThickness = new Thickness(1), Child = inner, VerticalAlignment = VerticalAlignment.Center,
        };
    }

    // Prism and MultiMC share one source kind and one tile ("Prism / MultiMC"), so the tile wears Prism's logo.
    private static Avalonia.Media.Imaging.Bitmap? LogoBitmap(SourceKind kind)
    {
        var name = kind switch
        {
            SourceKind.OfficialLauncher => "minecraft",
            SourceKind.Prism => "prism",
            SourceKind.Modrinth => "modrinth",
            SourceKind.CurseForge => "curseforge",
            SourceKind.Lunar => "lunar",
            SourceKind.Feather => "feather",
            _ => null,
        };
        return name == null ? null : Assets.Bitmap($"Assets/Logos/{name}.png");
    }

    private static readonly Dictionary<SourceKind, IBrush> Edges = new();

    /// <summary>The launcher's hue at 40 % (the WPF LauncherLogos.Edge): the soft ring around its logo.</summary>
    public static IBrush Edge(SourceKind kind)
    {
        if (Edges.TryGetValue(kind, out var b)) return b;
        var key = kind switch
        {
            SourceKind.OfficialLauncher => "Hue.Emerald",
            SourceKind.CurseForge => "Hue.Coral",
            SourceKind.Modrinth => "Hue.Gold",
            SourceKind.Prism => "Hue.Lilac",
            SourceKind.Lunar or SourceKind.Feather => "Hue.Sky",
            _ => "TextDim",
        };
        var c = Palette.Get(key);
        b = new ImmutableSolidColorBrush(Color.FromArgb(0x66, c.R, c.G, c.B));
        Edges[kind] = b;
        return b;
    }

    /// <summary>A dark stat box: the number (24) over its small-caps label ("INSTANCES").</summary>
    public static Border Stat(string value, string label, double minWidth = 0)
    {
        var number = Ui.Text(value, 24, FontWeight.SemiBold, Palette.Text, -0.01);
        number.HorizontalAlignment = HorizontalAlignment.Left;
        var caption = Ui.Eyebrow(label, Palette.TextDim, 0.02);
        caption.HorizontalAlignment = HorizontalAlignment.Left;
        return new Border
        {
            Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Md), Padding = new Thickness(8, 6, 8, 8),
            MinWidth = minWidth, Child = Ui.Column(2, number, caption),
        };
    }

    /// <summary>The four steps (Source, What to bring, Confirm, Report) with the current one ringed.</summary>
    public static Control Steps(int current)
    {
        var names = new[] { "Source", "What to bring", "Confirm", "Report" };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto,*,Auto,*,Auto"), MaxWidth = 600, HorizontalAlignment = HorizontalAlignment.Left };
        for (var i = 0; i < 4; i++)
        {
            var n = i + 1;
            var done = n < current;
            var cur = n == current;
            Control mark = done
                ? Ui.Icon(Icons.Check, 12, Palette.HueEmerald)
                : Ui.Text(Fmt.N(n), TypeScale.Label, FontWeight.SemiBold, cur ? Palette.Text : Palette.TextMuted);
            mark.HorizontalAlignment = HorizontalAlignment.Center;
            var dot = new Border
            {
                Width = 20, Height = 20, CornerRadius = new CornerRadius(10), Child = mark,
                Background = done ? Palette.TileEmerald : null,
                BorderBrush = done ? null : cur ? Palette.AccentLine : Palette.LineStrong,
                BorderThickness = new Thickness(1.5), Margin = new Thickness(0, 0, 8, 0),
            };
            var text = Ui.Text(names[i], TypeScale.Caption, FontWeight.SemiBold, cur ? Palette.Text : done ? Palette.TextDim : Palette.TextMuted);
            grid.Children.Add(Ui.Row(0, dot, text).At(i * 2));
            if (i < 3)
                grid.Children.Add(new Border { Height = 1, Background = Palette.LineStrong, Margin = new Thickness(12, 0), VerticalAlignment = VerticalAlignment.Center }.At(i * 2 + 1));
        }
        return grid;
    }

    /// <summary>A skeleton block (the tiles while the scan runs): bg fill, radius sm.</summary>
    public static Border Skeleton(double width, double height) => new()
    {
        Width = width, Height = height, Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Sm),
        HorizontalAlignment = HorizontalAlignment.Left,
    };

    /// <summary>A plain text button that sits in a line ("See what came over", "Pick single setups").</summary>
    public static Pressable Link(string label, string? tip = null)
    {
        var b = Ui.Button(label, Look.Ghost, height: Sizes.ControlMd);
        if (tip != null) b.Tip(tip);
        return b;
    }
}

/// <summary>
/// A tick box (the WPF Import.Tick): a 32 px hit area around a 22 px box, radius sm. Off: bg + stroke; hover: the
/// accent line; on: accent with a check; mixed (some of a group): accent with a minus; disabled: 45 %.
/// </summary>
internal sealed class Tick : Pressable
{
    private readonly Border _box = new()
    {
        Width = 22, Height = 22, CornerRadius = new CornerRadius(Radius.Sm), BorderThickness = new Thickness(1),
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
    };
    private readonly IconView _mark = new() { Size = 14, Brush = Palette.OnAccent, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
    private bool? _state = false;

    /// <param name="small">The row tick (the WPF Social.Check): an 18 px box in a 28 px hit area.</param>
    public Tick(string name, bool small = false) : base(Look.Plain, null, name)
    {
        Width = small ? Sizes.ControlSm : 32;
        Height = small ? Sizes.ControlSm : 32;
        CornerRadius = new CornerRadius(Radius.Md);
        Background = Brushes.Transparent;
        if (small)
        {
            _box.Width = _box.Height = 18;
            _mark.Size = 12;
        }
        _box.Child = _mark;
        Child = _box;
        StateChanged += _ => Paint();
        Paint();
    }

    /// <summary>true on, false off, null mixed.</summary>
    public bool? State
    {
        get => _state;
        set { _state = value; Paint(); }
    }

    private void Paint()
    {
        if (_box == null) return; // the base constructor paints before the fields exist
        var on = _state != false;
        var hot = (IsHovered || ForceHover) && IsEffectivelyEnabled;
        _box.Background = on ? Palette.Accent : Palette.Bg;
        _box.BorderBrush = on ? Palette.Accent : hot ? Palette.AccentLine : Palette.LineStrong;
        _mark.IsVisible = on;
        _mark.Glyph = _state == null ? Icons.Minus : Icons.Check;
        Opacity = IsEffectivelyEnabled ? 1 : 0.45;
    }
}
