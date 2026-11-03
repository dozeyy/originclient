using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.Core.Sync;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The Import page's list rows (the twin of the WPF Social.DrawerRow / Import.* row templates): a row is a
// transparent strip that lights on hover (a press opens or ticks it) or a static strip; rows stack 2 px apart
// under a section label. Pure builders: the page keeps references to what it changes in place.
internal static class ImportRows
{
    /// <summary>The older pages' column (the WPF Legacy grid): 720 wide, left-aligned.</summary>
    public const double ColumnWidth = 720;

    // A narrower column keeps its full width at the left (Stretch under a MaxWidth would centre it); the page-wide one
    // stretches. 720 always fits: the smallest window's column is 916 wide.
    public static StackPanel List(double maxWidth = ColumnWidth) => maxWidth < Sizes.PageMaxWidth
        ? new StackPanel { Spacing = 2, Width = maxWidth, HorizontalAlignment = HorizontalAlignment.Left }
        : new StackPanel { Spacing = 2, MaxWidth = maxWidth, HorizontalAlignment = HorizontalAlignment.Stretch };

    /// <summary>A row that does something when pressed (opens a version, ticks a setup).</summary>
    public static Pressable Button(Control content, string name, string? tip = null)
    {
        var p = new Pressable(Look.Row, content, name)
        {
            Padding = new Thickness(10, 8), CornerRadius = new CornerRadius(Radius.Md), MinHeight = 38,
        };
        if (tip != null) ToolTip.SetTip(p, tip);
        return p;
    }

    /// <summary>A row that only shows something (a stat, a note, a line of the report).</summary>
    public static Border Static(Control content) => new()
    {
        Padding = new Thickness(10, 8), MinHeight = 38, CornerRadius = new CornerRadius(Radius.Md), Child = content,
    };

    /// <summary>A quiet line in a row (the WPF HintVm / ImportNoteVm).</summary>
    public static Border Hint(string text, out TextBlock block)
    {
        block = ImportParts.Rich(text, Palette.TextDim, TypeScale.Caption);
        block.VerticalAlignment = VerticalAlignment.Center;
        return Static(block);
    }

    public static Border Hint(string text) => Hint(text, out _);

    /// <summary>A row's words: the title, then a caption, what it holds and a gold warning, each only when there.</summary>
    public static StackPanel Words(string title, string? caption, string? contents = null, string? warning = null, bool dimmed = false)
    {
        var col = new StackPanel { Spacing = 1, VerticalAlignment = VerticalAlignment.Center };
        var t = Ui.Text(title, TypeScale.Body, FontWeight.SemiBold, dimmed ? Palette.TextDim : Palette.Text);
        t.TextTrimming = TextTrimming.CharacterEllipsis;
        col.Children.Add(t);
        if (!string.IsNullOrEmpty(caption)) col.Children.Add(Ui.Wrap(Ui.Caption(caption, dimmed ? Palette.TextMuted : Palette.TextDim)));
        if (!string.IsNullOrEmpty(contents)) col.Children.Add(Ui.Wrap(Ui.Caption(contents, Palette.TextMuted)));
        if (!string.IsNullOrEmpty(warning)) col.Children.Add(Ui.Wrap(Ui.Caption(warning, Palette.Warning)));
        return col;
    }

    /// <summary>Words on the left, any number of controls on the right (a tick, a CHANGE button, the go chevron).</summary>
    public static Grid Line(Control left, params Control?[] right)
    {
        var cols = "*" + string.Concat(Enumerable.Repeat(",Auto", right.Length));
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions(cols) };
        grid.Children.Add(left.At(0));
        for (var i = 0; i < right.Length; i++)
        {
            if (right[i] is not { } c) continue;
            c.VerticalAlignment = VerticalAlignment.Center;
            if (c.Margin == default) c.Margin = new Thickness(12, 0, 0, 0);
            grid.Children.Add(c.At(i + 1));
        }
        return grid;
    }

    /// <summary>A label and its value (Review's COMING OVER rows).</summary>
    public static Border Stat(string label, string value)
    {
        var v = Ui.Text(value, TypeScale.Body, FontWeight.SemiBold);
        v.HorizontalAlignment = HorizontalAlignment.Right;
        return Static(Line(Ui.Body(label, Palette.TextDim), v));
    }

    /// <summary>A report line: title, caption, warning on the left; a coloured status word on the right.</summary>
    public static Border Report(string title, string? caption, string? warning = null, string? status = null, IBrush? statusBrush = null)
    {
        Control? right = null;
        if (!string.IsNullOrEmpty(status))
        {
            var s = Ui.Caption(status, statusBrush ?? Palette.TextDim);
            s.VerticalAlignment = VerticalAlignment.Top;
            s.Margin = new Thickness(16, 2, 0, 0);
            right = s;
        }
        var line = Line(Words(title, caption, null, warning), right);
        if (right != null) right.VerticalAlignment = VerticalAlignment.Top;
        return Static(line);
    }

    /// <summary>The initial box a setup row wears (the WPF Social.Avatar): 28, bg, radius md.</summary>
    public static Border Avatar(string name, bool dimmed = false) => new()
    {
        Width = 28, Height = 28, CornerRadius = new CornerRadius(Radius.Md), Background = Palette.Bg,
        BorderBrush = Palette.Line, BorderThickness = new Thickness(1), VerticalAlignment = VerticalAlignment.Top,
        Margin = new Thickness(0, 0, 10, 0), Opacity = dimmed ? 0.55 : 1,
        Child = Ui.Text(ImportLogic.Initial(name), TypeScale.Caption, FontWeight.SemiBold, Palette.TextDim).Align(HorizontalAlignment.Center),
    };

    /// <summary>A setup row's words with its initial box in front.</summary>
    public static Grid WithAvatar(string name, Control words, bool dimmed = false)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        grid.Children.Add(Avatar(name, dimmed).At(0));
        grid.Children.Add(words.At(1));
        return grid;
    }

    /// <summary>
    /// A "what to bring" chip (the WPF Import.Chip): a 28 capsule, a check when on (a dash when only some versions
    /// bring it), the category and its count. Off: hairline and dim words; disabled (nothing of it found): 50 %.
    /// </summary>
    public static Pressable Chip(string label, string? count, bool? on, bool enabled, string tip)
    {
        var mark = new IconView { Size = 12, Glyph = on == null ? Icons.Minus : Icons.Check, IsVisible = on != false, VerticalAlignment = VerticalAlignment.Center };
        var text = Ui.Text(string.IsNullOrEmpty(count) ? label : label + " " + count, TypeScale.Caption, FontWeight.Medium);
        var chip = new Pressable(Look.Plain, Ui.Row(6, mark, text), label)
        {
            Height = Sizes.ControlSm, Padding = new Thickness(on == false ? 12 : 10, 0, 12, 0), BorderThickness = new Thickness(1),
            Margin = new Thickness(0, 0, 6, 6), IsEnabled = enabled, VerticalAlignment = VerticalAlignment.Center,
        };
        ToolTip.SetTip(chip, tip);
        Ui.Capsule(chip);
        void Paint(Pressable p)
        {
            var hot = (p.IsHovered || p.ForceHover) && p.IsEffectivelyEnabled;
            var lit = on != false;
            p.Background = lit ? Palette.AccentSoft : hot ? Palette.CardHover : Palette.GhostRest;
            p.BorderBrush = lit ? (hot ? Palette.Accent : Palette.AccentLine) : hot ? Palette.LineHover : Palette.LineStrong;
            mark.Brush = Palette.Accent;
            text.Foreground = lit ? Palette.Text : Palette.TextDim;
            p.Opacity = p.IsEffectivelyEnabled ? 1 : 0.5;
        }
        chip.StateChanged += Paint;
        Paint(chip);
        return chip;
    }

    /// <summary>The setting row of Details: label and quiet caption, the switch on the right.</summary>
    public static Border Option(string label, string caption, Toggle toggle)
    {
        var words = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        words.Children.Add(Ui.Body(label));
        words.Children.Add(Ui.Wrap(Ui.Caption(caption, Palette.TextDim)));
        var row = Static(Line(words, toggle));
        row.Padding = new Thickness(10, 6);
        row.MinHeight = 40;
        return row;
    }
}
