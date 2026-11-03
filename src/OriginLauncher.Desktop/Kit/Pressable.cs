using Avalonia;
using Avalonia.Animation;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

/// <summary>The button variants of section 11.1 plus the row/card/tab looks of section 8.</summary>
public enum Look
{
    Primary,     // accent fill, onAccent label; one per decision area
    Secondary,   // surfaceRaised + lineStrong, text label; most actions
    Ghost,       // no fill, textDim label; tertiary, Cancel, icon buttons
    Danger,      // dangerSoft fill, dangerText label; never filled red
    ChromeClose, // ghost, hover dangerSoft + dangerText glyph (window close)
    Card,        // surfaceRaised + line; hover surfaceHover + lineStrong; no press scale (large targets never move)
    Row,         // transparent row; hover surfaceHover; no press scale
    Plain,       // no visuals at all: the caller draws every state (Play, version cards, the switch)
}

// Every clickable thing in the launcher: one control, one state contract (DESIGN-SYSTEM.md section 8).
// A Border, not a templated Button, so each look is drawn by this file alone: rest / hover / press
// (scale .97 for buttons) / keyboard focus ring (2 px gap + 2 px accentLine, never on pointer clicks) /
// selected / disabled (no hover, default cursor). Click fires on release over the control, or Enter/Space.
public class Pressable : Border
{
    public static readonly StyledProperty<Look> LookProperty =
        AvaloniaProperty.Register<Pressable, Look>(nameof(Look), Look.Secondary);
    public static readonly StyledProperty<bool> IsSelectedProperty =
        AvaloniaProperty.Register<Pressable, bool>(nameof(IsSelected));

    public event EventHandler<RoutedEventArgs>? Click;

    /// <summary>Raised when hover, press, focus, selection or enabled state changes (custom-drawn looks).</summary>
    public event Action<Pressable>? StateChanged;

    public Look Look { get => GetValue(LookProperty); set => SetValue(LookProperty, value); }
    public bool IsSelected { get => GetValue(IsSelectedProperty); set => SetValue(IsSelectedProperty, value); }

    public bool IsHovered { get; private set; }
    public bool IsPressed { get; private set; }
    public bool FocusVisible { get; private set; }

    /// <summary>Hover forced on for screenshots of the hover state (no pointer exists in a render run).</summary>
    public bool ForceHover { get => _forceHover; set { _forceHover = value; Apply(); } }
    private bool _forceHover;

    /// <summary>Keyboard focus ring forced on (render runs: no keyboard).</summary>
    public bool ForceFocus { get => _forceFocus; set { _forceFocus = value; Apply(); } }
    private bool _forceFocus;

    private readonly ScaleTransformHolder _scale = new();

    public Pressable()
    {
        Focusable = true;
        IsTabStop = true;
        Cursor = new Cursor(StandardCursorType.Hand);
        RenderTransformOrigin = RelativePoint.Center;
        if (!Motion.Off)
        {
            Transitions =
            [
                new BrushTransition { Property = BackgroundProperty, Duration = Motion.Hover },
                new BrushTransition { Property = BorderBrushProperty, Duration = Motion.Hover },
                new TransformOperationsTransition { Property = RenderTransformProperty, Duration = Motion.Press },
            ];
        }
        Apply();
    }

    public Pressable(Look look, object? child = null, string? name = null) : this()
    {
        Look = look;
        if (child is Control c) Child = c;
        else if (child is string s) Child = Ui.Label(s, look);
        if (name != null) AutomationProperties.SetName(this, name);
        Apply();
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == LookProperty || change.Property == IsSelectedProperty || change.Property == IsEffectivelyEnabledProperty)
        {
            Cursor = IsEffectivelyEnabled ? new Cursor(StandardCursorType.Hand) : Cursor.Default;
            Apply();
        }
    }

    protected override void OnPointerEntered(PointerEventArgs e)
    {
        base.OnPointerEntered(e);
        IsHovered = true;
        Apply();
    }

    protected override void OnPointerExited(PointerEventArgs e)
    {
        base.OnPointerExited(e);
        IsHovered = false;
        Apply();
    }

    protected override void OnPointerPressed(PointerPressedEventArgs e)
    {
        base.OnPointerPressed(e);
        if (!e.GetCurrentPoint(this).Properties.IsLeftButtonPressed) return;
        IsPressed = true;
        FocusVisible = false;
        e.Pointer.Capture(this);
        e.Handled = true;
        Apply();
    }

    protected override void OnPointerReleased(PointerReleasedEventArgs e)
    {
        base.OnPointerReleased(e);
        if (!IsPressed) return;
        IsPressed = false;
        e.Pointer.Capture(null);
        var p = e.GetPosition(this);
        var inside = p.X >= 0 && p.Y >= 0 && p.X <= Bounds.Width && p.Y <= Bounds.Height;
        e.Handled = true;
        Apply();
        if (inside) RaiseClick();
    }

    protected override void OnPointerCaptureLost(PointerCaptureLostEventArgs e)
    {
        base.OnPointerCaptureLost(e);
        if (!IsPressed) return;
        IsPressed = false;
        Apply();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Handled || e.Key is not (Key.Enter or Key.Space)) return;
        e.Handled = true;
        RaiseClick();
    }

    protected override void OnGotFocus(FocusChangedEventArgs e)
    {
        base.OnGotFocus(e);
        FocusVisible = e.NavigationMethod is NavigationMethod.Tab or NavigationMethod.Directional;
        Apply();
    }

    protected override void OnLostFocus(FocusChangedEventArgs e)
    {
        base.OnLostFocus(e);
        FocusVisible = false;
        Apply();
    }

    /// <summary>Run the click as if pressed (keyboard, render runs).</summary>
    public void RaiseClick()
    {
        if (!IsEffectivelyEnabled) return;
        Click?.Invoke(this, new RoutedEventArgs());
    }

    private bool Hot => (IsHovered || _forceHover) && IsEffectivelyEnabled;

    /// <summary>Repaint this look's state. Public so a parent can re-skin after changing content.</summary>
    public void Apply()
    {
        var enabled = IsEffectivelyEnabled;
        var hot = Hot;
        var pressed = IsPressed && enabled;
        var look = Look;
        IBrush? bg, edge;
        IBrush fg;
        switch (look)
        {
            case Look.Primary:
                bg = !enabled ? Palette.Card : pressed ? Palette.AccentPress : hot ? Palette.AccentHover : Palette.Accent;
                edge = null;
                fg = enabled ? Palette.OnAccent : Palette.TextMuted;
                break;
            case Look.Secondary:
                bg = !enabled ? Palette.Card : pressed ? Palette.SurfacePress : hot ? Palette.CardHover : Palette.Card;
                edge = !enabled ? Palette.Line : hot ? Palette.LineHover : Palette.LineStrong;
                fg = enabled ? Palette.Text : Palette.TextMuted;
                break;
            case Look.Danger:
                bg = !enabled ? Palette.Card : hot || pressed ? Palette.DangerSoftHover : Palette.DangerSoft;
                edge = null;
                fg = enabled ? Palette.DangerText : Palette.TextMuted;
                break;
            case Look.ChromeClose:
                bg = !enabled ? Palette.GhostRest : pressed ? Palette.DangerSoftHover : hot ? Palette.DangerSoft : Palette.GhostRest;
                edge = null;
                fg = hot || pressed ? Palette.DangerText : Palette.TextDim;
                break;
            case Look.Card:
                bg = hot ? Palette.CardHover : Palette.Card;
                edge = IsSelected ? Palette.Accent : hot ? Palette.LineStrong : Palette.Line;
                fg = Palette.Text;
                break;
            case Look.Row:
                bg = IsSelected ? Palette.AccentSoft : hot ? Palette.CardHover : Palette.GhostRest;
                edge = null;
                fg = Palette.Text;
                break;
            case Look.Plain:
                bg = null; edge = null; fg = Palette.Text;
                StateChanged?.Invoke(this);
                ApplyChrome(enabled, pressed, look);
                return;
            default: // Ghost
                bg = !enabled ? Palette.GhostRest : pressed ? Palette.GhostPress : hot ? Palette.GhostHover : Palette.GhostRest;
                edge = null;
                fg = !enabled ? Palette.TextMuted : hot || IsSelected ? Palette.Text : Palette.TextDim;
                break;
        }
        Background = bg;
        BorderBrush = edge;
        if (edge != null && BorderThickness == default) BorderThickness = new Thickness(1);
        TextElement.SetForeground(this, fg);
        Opacity = !enabled && look is Look.Ghost or Look.ChromeClose ? 0.6 : !enabled && look is Look.Card or Look.Row ? 0.5 : 1;
        ApplyChrome(enabled, pressed, look);
        StateChanged?.Invoke(this);
    }

    private void ApplyChrome(bool enabled, bool pressed, Look look)
    {
        var scales = look is Look.Primary or Look.Secondary or Look.Ghost or Look.Danger or Look.ChromeClose;
        RenderTransform = scales && pressed ? _scale.Pressed : _scale.Rest;
        BoxShadow = (FocusVisible || _forceFocus) && enabled ? Palette.FocusRing : default;
    }

    private sealed class ScaleTransformHolder
    {
        public readonly TransformOperations Rest = TransformOperations.Parse("scale(1)");
        public readonly TransformOperations Pressed = TransformOperations.Parse("scale(0.97)");
    }
}
