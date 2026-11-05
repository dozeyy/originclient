using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.VisualTree;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

// The Windows launcher's keyboard (WPF MainWindow_PreviewKeyDown), the Mac way (WP-6; DECISIONS 2026-11-02: the Mac
// launcher has every feature of the Windows one): Cmd+1/2/3/4 open Home, Mods, Profiles, Settings (Cmd+, too, the
// Mac's own Settings key), and Enter on Home plays. Closing the window saves what Settings still has typed (WP-5).
public sealed partial class MainWindow
{
    private void HookKeys()
    {
        // Bubble: a control that uses the key itself (a text box, a dropdown, an overlay's own Enter) gets it first.
        AddHandler(KeyDownEvent, PageKeys_KeyDown, RoutingStrategies.Bubble);
        // The Java arguments and resolution boxes save on LostFocus, which Cmd+W / Cmd+Q / the red button never
        // raise: without this the next Play ran without what the player had just typed (WPF: Closing -> FlushEdits).
        Closing += (_, _) => Settings.FlushEdits();
    }

    private void PageKeys_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Handled) return;
        // The opening intro, the confirm dialog and the account/sign-in cards own the keyboard while they show.
        if (Root.Children.OfType<StartupIntro>().Any() || Confirm.IsVisible || _accountPanelOpen || _signInPanelOpen) return;
        // The launch cover is modal within Home: no page switch and no second Play behind a starting game.
        if (_currentPage == LauncherPage.Home && Home.HasLaunchCover) return;

        if (e.KeyModifiers == KeyModifiers.Meta)
        {
            LauncherPage? target = e.Key switch
            {
                Key.D1 or Key.NumPad1 => LauncherPage.Home,
                Key.D2 or Key.NumPad2 => LauncherPage.Mods,
                Key.D3 or Key.NumPad3 => LauncherPage.Profiles,
                Key.D4 or Key.NumPad4 or Key.OemComma => LauncherPage.Settings,
                _ => null,
            };
            if (target is { } page)
            {
                ShowPage(page);
                e.Handled = true;
            }
            return;
        }

        if (e.Key == Key.Enter && e.KeyModifiers == KeyModifiers.None && _currentPage == LauncherPage.Home
            && !_importOpen && !_friendsOpen && !IsTypingOrPressing(e.Source))
        {
            e.Handled = Home.TryLaunchFromKeyboard();
        }
    }

    // Enter in a text box, a dropdown or on a focused button is that control's own (WPF: TextBoxBase, Selector, ButtonBase).
    private static bool IsTypingOrPressing(object? source) =>
        source is Visual visual
        && visual.GetSelfAndVisualAncestors().Any(v => v is TextBox or Button or SelectingItemsControl);
}
