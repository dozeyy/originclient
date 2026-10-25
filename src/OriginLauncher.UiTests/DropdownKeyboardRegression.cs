using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Xunit;

namespace OriginLauncher.UiTests;

/// <summary>
/// Phase 3 U3.7b: every dropdown opens, selects and closes by keyboard with the restyled template (Theme/Inputs.xaml,
/// Input.ComboBox). The restyle replaced the whole ComboBox template, so a missing popup or a toggle that takes
/// the keys would break F4 / arrows / Esc without any compile error. WPF refuses to open a dropdown until the box
/// IsLoaded, and a box only loads inside a shown window, so this puts the box in a small borderless window parked
/// off-screen for a moment (CI's desktop; do not run this on a PC where a window would be a nuisance).
/// Runs inside CrashWindowFallbackTests' STA body, after the theme dictionaries are merged, because a process can
/// have only one Application and that test owns it.
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
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Top,
        };
        foreach (var version in new[] { "1.21.1", "1.20.4", "1.19.4" }) combo.Items.Add(version);
        combo.SelectedIndex = 0;

        var window = new Window
        {
            WindowStyle = WindowStyle.None,
            ShowInTaskbar = false,
            ShowActivated = false,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Left = -10000,
            Top = -10000,
            Width = 240,
            Height = 60,
            Content = combo,
        };
        try
        {
            window.Show();
            window.Dispatcher.Invoke(() => { }, DispatcherPriority.ContextIdle);
            Assert.True(combo.IsLoaded);
            Assert.NotNull(combo.Template.FindName("Popup", combo)); // the template keeps the popup the box opens

            // F4 opens, F4 again closes.
            Press(combo, Key.F4);
            Assert.True(combo.IsDropDownOpen);
            Press(combo, Key.F4);
            Assert.False(combo.IsDropDownOpen);

            // Esc closes an open list and leaves the choice alone.
            Press(combo, Key.F4);
            Assert.True(combo.IsDropDownOpen);
            Press(combo, Key.Escape);
            Assert.False(combo.IsDropDownOpen);
            Assert.Equal(0, combo.SelectedIndex);

            // The keyboard can change the choice: Down on the closed box selects the next item; failing that (a WPF
            // build that only moves the highlight), open the list, move the highlight with Down and commit with Enter.
            var before = combo.SelectedIndex;
            Press(combo, Key.Down);
            var route = "Down on the closed box";
            if (combo.SelectedIndex == before)
            {
                route = "F4, Down, Enter";
                Press(combo, Key.F4);
                Press(combo, Key.Down);
                Press(combo, Key.Enter);
            }
            Assert.True(combo.SelectedIndex != before,
                $"the choice stayed at {before} after {route} (list open: {combo.IsDropDownOpen}, selected now {combo.SelectedIndex})");
            Assert.False(combo.IsDropDownOpen);
        }
        finally
        {
            window.Close();
        }
    }

    private static void Press(UIElement target, Key key) =>
        target.RaiseEvent(new KeyEventArgs(Keyboard.PrimaryDevice, PresentationSource.FromVisual(target), Environment.TickCount, key)
        {
            RoutedEvent = Keyboard.KeyDownEvent,
        });
}
