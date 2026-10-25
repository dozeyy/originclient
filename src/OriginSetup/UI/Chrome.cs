using System.Windows;
using System.Windows.Input;
using System.Windows.Media;

namespace OriginSetup.UI;

// Two per-control hooks the Workbench templates (Theme/Controls.xaml) read:
//
//  Corner / RingCorner  one template serves every size step; the size style sets its corner radius token
//                       (spec 6) and the matching focus-ring corner (radius + 2, spec 8).
//  KeyFocus             true only while the control holds KEYBOARD focus. Spec 8: pointer clicks never draw
//                       the focus ring. WPF's own FocusVisualStyle can't be drawn into the CI screenshot
//                       (adorners are not part of the rendered tree), so the ring is part of the template and
//                       this property turns it on. The tour sets it by hand to show the ring.
public static class Chrome
{
    public static readonly DependencyProperty CornerProperty = DependencyProperty.RegisterAttached(
        "Corner", typeof(CornerRadius), typeof(Chrome), new FrameworkPropertyMetadata(new CornerRadius()));

    public static readonly DependencyProperty RingCornerProperty = DependencyProperty.RegisterAttached(
        "RingCorner", typeof(CornerRadius), typeof(Chrome), new FrameworkPropertyMetadata(new CornerRadius()));

    // The fill of a ticked checkbox: accent normally, danger for the uninstaller's "delete my data" box.
    public static readonly DependencyProperty CheckFillProperty = DependencyProperty.RegisterAttached(
        "CheckFill", typeof(Brush), typeof(Chrome), new FrameworkPropertyMetadata(null));

    public static readonly DependencyProperty KeyFocusProperty = DependencyProperty.RegisterAttached(
        "KeyFocus", typeof(bool), typeof(Chrome), new FrameworkPropertyMetadata(false));

    public static CornerRadius GetCorner(DependencyObject d) => (CornerRadius)d.GetValue(CornerProperty);
    public static void SetCorner(DependencyObject d, CornerRadius v) => d.SetValue(CornerProperty, v);
    public static CornerRadius GetRingCorner(DependencyObject d) => (CornerRadius)d.GetValue(RingCornerProperty);
    public static void SetRingCorner(DependencyObject d, CornerRadius v) => d.SetValue(RingCornerProperty, v);
    public static Brush? GetCheckFill(DependencyObject d) => (Brush?)d.GetValue(CheckFillProperty);
    public static void SetCheckFill(DependencyObject d, Brush? v) => d.SetValue(CheckFillProperty, v);
    public static bool GetKeyFocus(DependencyObject d) => (bool)d.GetValue(KeyFocusProperty);
    public static void SetKeyFocus(DependencyObject d, bool v) => d.SetValue(KeyFocusProperty, v);

    static Chrome()
    {
        // Registered once, the first time any template reads a Chrome property.
        EventManager.RegisterClassHandler(typeof(UIElement), Keyboard.GotKeyboardFocusEvent,
            new KeyboardFocusChangedEventHandler((s, e) =>
            {
                // Focus that arrives by Tab / arrows shows the ring; focus from a click or from code does not.
                if (s is DependencyObject d && ReferenceEquals(s, e.NewFocus))
                    SetKeyFocus(d, InputManager.Current.MostRecentInputDevice is KeyboardDevice);
            }));
        EventManager.RegisterClassHandler(typeof(UIElement), Keyboard.LostKeyboardFocusEvent,
            new KeyboardFocusChangedEventHandler((s, e) =>
            {
                if (s is DependencyObject d && ReferenceEquals(s, e.OldFocus)) SetKeyFocus(d, false);
            }));
    }
}
