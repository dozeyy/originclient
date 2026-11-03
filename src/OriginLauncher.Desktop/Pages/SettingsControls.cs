using Avalonia;
using Avalonia.Animation;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

// The Settings page's own pieces (WPF SettingsPage.xaml styles: SettingCard, SectionTitle, SettingTitle/Why,
// RailItem, the RAM slider, the text boxes and their error line). Only SettingsPage uses them.
internal static class SettingsUi
{
    /// <summary>SettingCard: panel fill, clear 1 px edge, radius lg, 16 inside, 8 below, 64 least; lifts to card on hover.</summary>
    public static Border Card(Control child)
    {
        var card = new Border
        {
            Background = Palette.Panel,
            BorderBrush = Brushes.Transparent,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Lg),
            Padding = new Thickness(Space.S4),
            Margin = new Thickness(0, 0, 0, Space.S2),
            MinHeight = Sizes.Row,
            Child = child,
        };
        if (!Motion.Off)
            card.Transitions = [new BrushTransition { Property = Border.BackgroundProperty, Duration = Motion.Hover }];
        card.PointerEntered += (_, _) => card.Background = Palette.Card;
        card.PointerExited += (_, _) => card.Background = Palette.Panel;
        return card;
    }

    /// <summary>SectionTitle: the label role in textDim, 32 above (24 for the first), 10 below.</summary>
    public static TextBlock SectionTitle(string text, bool first = false) =>
        Ui.Eyebrow(text, Palette.TextDim).Margin(0, first ? Space.S6 : Space.S8, 0, 10).Align(HorizontalAlignment.Left, VerticalAlignment.Top);

    /// <summary>A setting's title (bodyStrong) over its one-line reason (caption, wraps).</summary>
    public static StackPanel TitleWhy(string title, string? why, out TextBlock? whyText)
    {
        var s = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        s.Children.Add(Ui.BodyStrong(title, Palette.Text).Align(HorizontalAlignment.Left, VerticalAlignment.Top));
        whyText = null;
        if (why != null)
        {
            whyText = Why(why);
            s.Children.Add(whyText);
        }
        return s;
    }

    public static StackPanel TitleWhy(string title, string? why = null) => TitleWhy(title, why, out _);

    /// <summary>SettingWhy: caption in textDim, wraps, 2 below the title.</summary>
    public static TextBlock Why(string text)
    {
        var t = Ui.Wrap(Ui.Caption(text));
        t.Margin = new Thickness(0, 2, 0, 0);
        return t;
    }

    /// <summary>Title block on the left, one control on the right (the common card row).</summary>
    public static Grid Row(Control left, Control right)
    {
        var g = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        left.Margin = new Thickness(0, 0, Space.S4, 0);
        left.VerticalAlignment = VerticalAlignment.Center;
        right.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(left.At(0));
        g.Children.Add(right.At(1));
        return g;
    }

    /// <summary>The line under a field that says what is wrong (Input.ErrorText): caption in dangerText, hidden when fine.</summary>
    public static TextBlock ErrorText()
    {
        var t = Ui.Wrap(Ui.Caption("", Palette.DangerText));
        t.Margin = new Thickness(2, Space.S1, 0, 0);
        t.IsVisible = false;
        return t;
    }

    public static void ShowError(TextBlock line, string? problem)
    {
        line.Text = problem ?? "";
        line.IsVisible = problem != null;
    }

    /// <summary>A text box in the Workbench look (Fluent underneath, re-tinted in DesktopApp): field fill, lineStrong
    /// edge, accentLine when focused (1 px, never Fluent's 2), muted when disabled.</summary>
    public static TextBox Box(string accessibleName, double width = double.NaN, bool centred = false)
    {
        var t = new TextBox
        {
            FontFamily = Assets.Inter,
            FontSize = TypeScale.Body,
            MinHeight = Sizes.ControlLg,
            Width = width,
            Padding = new Thickness(Space.S3, 0),
            VerticalContentAlignment = VerticalAlignment.Center,
            CornerRadius = new CornerRadius(Radius.Md),
            BorderThickness = new Thickness(1),
        };
        if (centred) t.TextAlignment = TextAlignment.Center;
        t.Resources["TextControlBorderThemeThicknessFocused"] = new Thickness(1);
        t.Resources["TextControlBackgroundDisabled"] = Palette.Field;
        t.Resources["TextControlBorderBrushDisabled"] = Palette.Line;
        t.Resources["TextControlForegroundDisabled"] = Palette.TextMuted;
        AutomationProperties.SetName(t, accessibleName);
        return t;
    }

    private static readonly string[] EdgeKeys =
        ["TextControlBorderBrush", "TextControlBorderBrushPointerOver", "TextControlBorderBrushFocused"];

    /// <summary>The Danger edge on a field with a problem (WPF Chrome.SetInvalid).</summary>
    public static void SetInvalid(TextBox box, bool invalid)
    {
        if (invalid == IsInvalid(box)) return;
        box.Classes.Set("invalid", invalid);
        foreach (var key in EdgeKeys)
        {
            if (invalid) box.Resources[key] = Palette.Danger;
            else box.Resources.Remove(key);
        }
    }

    public static bool IsInvalid(TextBox box) => box.Classes.Contains("invalid");
}

/// <summary>A Settings rail item (WPF RailItem): 28 tall, hover panel fill, the lit one gets a 2 px accent bar on the
/// left and text ink; icon 14 in its hue, the label in tracked caps.</summary>
internal sealed class SettingsRailItem : Pressable
{
    private readonly Border _bar;
    private readonly TextBlock _label;

    public string Section { get; }

    public SettingsRailItem(string label, string glyph, IBrush hue, string section) : base(Look.Plain, null, label)
    {
        Section = section;
        Height = Sizes.ControlSm;
        CornerRadius = new CornerRadius(Radius.Md);
        HorizontalAlignment = HorizontalAlignment.Stretch;
        _bar = new Border
        {
            Width = 2, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 9),
            Background = Palette.Accent, CornerRadius = new CornerRadius(1),
        };
        _label = Ui.Eyebrow(label, Palette.TextMuted, 0.16);
        var row = Ui.Row(10, Ui.Icon(glyph, Sizes.IconSm, hue), _label);
        row.Margin = new Thickness(Space.S3, 0, 0, 0);
        var panel = new Panel();
        panel.Children.Add(_bar);
        panel.Children.Add(row);
        Child = panel;
        StateChanged += _ => Paint();
        Paint();
    }

    private void Paint()
    {
        if (_bar == null) return; // base constructor runs Apply before the fields exist
        var hot = (IsHovered || ForceHover) && IsEffectivelyEnabled;
        // Kit's Look.Plain returns before touching the edge, so the default look's border (painted by the base
        // constructor) would stay; the rail has no edge.
        BorderBrush = null;
        BorderThickness = default;
        Background = hot ? Palette.Panel : Brushes.Transparent;
        _bar.IsVisible = IsSelected;
        _label.Foreground = IsSelected || hot ? Palette.Text : Palette.TextMuted;
    }
}

/// <summary>The RAM slider (WPF Slider in the Workbench skin): 4 px progress-track rail with an accent fill, a 16 px
/// accent knob (hover accentHover, drag accentPress), keyboard focus ring. Snaps to <see cref="Step"/> from the
/// minimum. <see cref="ValueChanged"/> fires only for the player's own drag or keys; setting Value never raises it.</summary>
internal sealed class SettingsSlider : Control
{
    private double _value;
    private bool _hover, _drag, _focusVisible;

    public double Minimum { get; set; }
    public double Maximum { get; set; } = 1;
    public double Step { get; set; } = 1;
    public double LargeStep { get; set; } = 1;

    public event Action<int>? ValueChanged;

    public SettingsSlider(string accessibleName)
    {
        Focusable = true;
        IsTabStop = true;
        Height = Sizes.SliderBox;
        Cursor = new Cursor(StandardCursorType.Hand);
        AutomationProperties.SetName(this, accessibleName);
    }

    public double Value
    {
        get => _value;
        set { _value = Fit(value); InvalidateVisual(); }
    }

    private double Fit(double v)
    {
        var max = Math.Max(Minimum, Maximum);
        if (Step > 0) v = Minimum + Math.Round((v - Minimum) / Step) * Step;
        return Math.Clamp(v, Minimum, max);
    }

    private void SetByPlayer(double v)
    {
        var next = Fit(v);
        if (next == _value) return;
        _value = next;
        InvalidateVisual();
        ValueChanged?.Invoke((int)next);
    }

    private double TrackStart => Sizes.SliderKnob / 2;
    private double TrackLength => Math.Max(1, Bounds.Width - Sizes.SliderKnob);

    private void SetFromX(double x)
    {
        var f = Math.Clamp((x - TrackStart) / TrackLength, 0, 1);
        SetByPlayer(Minimum + f * (Maximum - Minimum));
    }

    protected override void OnPointerEntered(PointerEventArgs e) { base.OnPointerEntered(e); _hover = true; InvalidateVisual(); }
    protected override void OnPointerExited(PointerEventArgs e) { base.OnPointerExited(e); _hover = false; InvalidateVisual(); }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (!IsEffectivelyEnabled || !e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        _drag = true;
        _focusVisible = false;
        Focus();
        e.Pointer.Capture(this);
        SetFromX(e.GetPosition(this).X);
        e.Handled = true;
    }

    protected override void OnPointerMoved(PointerEventArgs e)
    {
        base.OnPointerMoved(e);
        if (_drag) SetFromX(e.GetPosition(this).X);
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        if (!_drag) return;
        _drag = false;
        e.Pointer.Capture(null);
        InvalidateVisual();
    }

    protected override void OnPointerCaptureLost(PointerCaptureLostEventArgs e)
    {
        base.OnPointerCaptureLost(e);
        _drag = false;
        InvalidateVisual();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Handled || !IsEffectivelyEnabled) return;
        double? to = e.Key switch
        {
            Key.Left or Key.Down => _value - Step,
            Key.Right or Key.Up => _value + Step,
            Key.PageDown => _value - LargeStep,
            Key.PageUp => _value + LargeStep,
            Key.Home => Minimum,
            Key.End => Maximum,
            _ => null,
        };
        if (to is not { } v) return;
        e.Handled = true;
        SetByPlayer(v);
    }

    protected override void OnGotFocus(FocusChangedEventArgs e)
    {
        base.OnGotFocus(e);
        _focusVisible = e.NavigationMethod is NavigationMethod.Tab or NavigationMethod.Directional;
        InvalidateVisual();
    }

    protected override void OnLostFocus(FocusChangedEventArgs e)
    {
        base.OnLostFocus(e);
        _focusVisible = false;
        InvalidateVisual();
    }

    public override void Render(DrawingContext context)
    {
        var h = Bounds.Height;
        var cy = h / 2;
        var span = Maximum - Minimum;
        var f = span > 0 ? Math.Clamp((_value - Minimum) / span, 0, 1) : 0;
        var x = TrackStart + f * TrackLength;
        var track = Sizes.SliderTrack;
        var r = track / 2;
        context.DrawRectangle(Palette.ProgressTrack, null, new RoundedRect(new Rect(TrackStart, cy - r, TrackLength, track), r));
        if (x > TrackStart)
            context.DrawRectangle(Palette.Accent, null, new RoundedRect(new Rect(TrackStart, cy - r, x - TrackStart, track), r));
        var enabled = IsEffectivelyEnabled;
        var knob = !enabled ? Palette.TextMuted : _drag ? Palette.AccentPress : _hover ? Palette.AccentHover : Palette.Accent;
        var k = Sizes.SliderKnob / 2;
        if (_focusVisible && enabled)
            context.DrawEllipse(null, new Pen(Palette.AccentLine, 2), new Point(x, cy), k + 3, k + 3);
        context.DrawEllipse(knob, null, new Point(x, cy), k, k);
    }
}
