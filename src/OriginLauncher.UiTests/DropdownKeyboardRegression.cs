using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using Xunit;

namespace OriginLauncher.UiTests;

/// <summary>
/// Phase 3 U3.7b: every dropdown opens, selects and closes by keyboard with the restyled template (Theme/Inputs.xaml,
/// Input.ComboBox). The restyle replaced the whole ComboBox template, so a missing PART_Popup or a toggle that takes
/// the keys would break F4 / arrows / Esc without any compile error. The box sits in a hidden HwndSource (never shown),
/// which gives key events the PresentationSource they need. Runs inside CrashWindowFallbackTests' STA body, after the
/// theme dictionaries are merged, because a process can have only one Application and that test owns it.
/// </summary>
internal static class DropdownKeyboardRegression
{
    internal static void Run()
    {
        var app = Application.Current;
        var combo = new ComboBox
        {
            Style = (Style)app.FindResource("Input.ComboBox"),
            ItemContainerStyle = (Style)app.FindResource("Input.ComboBoxItem"),
            Width = 200,
        };
        foreach (var version in new[] { "1.21.1", "1.20.4", "1.19.4" }) combo.Items.Add(version);
        combo.SelectedIndex = 0;

        using var source = new HwndSource(new HwndSourceParameters("dropdown-keyboard-test", 240, 40)) { RootVisual = combo };
        combo.ApplyTemplate();
        combo.UpdateLayout();
        Assert.NotNull(combo.Template.FindName("PART_Popup", combo)); // the template keeps the popup the box opens

        // F4 opens, F4 again closes.
        Press(combo, source, Key.F4);
        Assert.True(combo.IsDropDownOpen);
        Press(combo, source, Key.F4);
        Assert.False(combo.IsDropDownOpen);

        // Esc closes an open list and leaves the choice alone.
        Press(combo, source, Key.F4);
        Assert.True(combo.IsDropDownOpen);
        Press(combo, source, Key.Escape);
        Assert.False(combo.IsDropDownOpen);
        Assert.Equal(0, combo.SelectedIndex);

        // Closed, Down and Up select the next and the previous item.
        Press(combo, source, Key.Down);
        Assert.Equal(1, combo.SelectedIndex);
        Press(combo, source, Key.Down);
        Assert.Equal(2, combo.SelectedIndex);
        Press(combo, source, Key.Up);
        Assert.Equal(1, combo.SelectedIndex);
    }

    private static void Press(UIElement target, PresentationSource source, Key key) =>
        target.RaiseEvent(new KeyEventArgs(Keyboard.PrimaryDevice, source, Environment.TickCount, key) { RoutedEvent = Keyboard.KeyDownEvent });
}
