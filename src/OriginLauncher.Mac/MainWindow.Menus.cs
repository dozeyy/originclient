using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

// The Mac menu bar's Edit and View menus and the app menu's Settings... (the Mac way to reach what the Windows launcher
// reaches by keyboard; DECISIONS 2026-11-02: every Windows feature on the Mac).
//
// Edit: Undo, Redo, Cut, Copy, Paste and Select All with their usual Cmd keys. A menu item's key equivalent takes the
// keystroke before any view sees it, so every item routes itself:
//   - while the Microsoft sign-in card is open: to the responder chain (MacNative.SendEditAction), where the card's
//     WKWebView takes it. Avalonia never hands that native view a Cmd key combination (Avalonia issue 19000), so
//     without this a player could not paste their email or password into Microsoft's page;
//   - otherwise: to the focused text box (Settings' Java arguments, the Friends chat), the same TextBox methods its own
//     Cmd keys call.
public sealed partial class MainWindow
{
    private enum EditAction { Undo, Redo, Cut, Copy, Paste, SelectAll }

    private EditAction _lastEdit;
    private DateTime _lastEditAt;
    private bool _lastEditFromMenu;

    private void HookMenus()
    {
        // The same Cmd keys, should one reach Avalonia instead of the menu while the sign-in page has focus (the native
        // view passes up what it does not handle): one path for both, and a keystroke both paths saw runs once (RunEdit
        // drops the other path's copy within 150 ms; a held key's own repeats all run).
        AddHandler(KeyDownEvent, SignInEditKeys_KeyDown, RoutingStrategies.Tunnel);
        PropertyChanged += (_, e) =>
        {
            if (e.Property == WindowStateProperty) OnWindowStateChanged();
        };
    }

    private void SignInEditKeys_KeyDown(object? sender, KeyEventArgs e)
    {
        if (!_signInPanelOpen || e.Handled) return;
        EditAction? action = (e.KeyModifiers, e.Key) switch
        {
            (KeyModifiers.Meta, Key.Z) => EditAction.Undo,
            (KeyModifiers.Meta | KeyModifiers.Shift, Key.Z) => EditAction.Redo,
            (KeyModifiers.Meta, Key.X) => EditAction.Cut,
            (KeyModifiers.Meta, Key.C) => EditAction.Copy,
            (KeyModifiers.Meta, Key.V) => EditAction.Paste,
            (KeyModifiers.Meta, Key.A) => EditAction.SelectAll,
            _ => null,
        };
        if (action is not { } a) return;
        RunEdit(a, fromMenu: false);
        e.Handled = true;
    }

    private void Undo_Click(object? sender, EventArgs e) => RunEdit(EditAction.Undo);
    private void Redo_Click(object? sender, EventArgs e) => RunEdit(EditAction.Redo);
    private void Cut_Click(object? sender, EventArgs e) => RunEdit(EditAction.Cut);
    private void Copy_Click(object? sender, EventArgs e) => RunEdit(EditAction.Copy);
    private void Paste_Click(object? sender, EventArgs e) => RunEdit(EditAction.Paste);
    private void SelectAll_Click(object? sender, EventArgs e) => RunEdit(EditAction.SelectAll);

    private void RunEdit(EditAction action, bool fromMenu = true)
    {
        var now = DateTime.UtcNow;
        if (action == _lastEdit && fromMenu != _lastEditFromMenu && (now - _lastEditAt).TotalMilliseconds < 150) return;
        _lastEdit = action;
        _lastEditAt = now;
        _lastEditFromMenu = fromMenu;
        EditRoutedTo = Route(action);
    }

    // Where the action went (--motion-check reads it): "page", "textbox" or "none".
    internal string EditRoutedTo { get; private set; } = "none";

    private string Route(EditAction action)
    {
        // The sign-in card first: the web view's focus is invisible to Avalonia, and a text box focused behind the card
        // (Settings) must never receive what the player meant for Microsoft's page.
        if (_signInPanelOpen)
        {
            MacNative.SendEditAction(action switch
            {
                EditAction.Undo => "undo:",
                EditAction.Redo => "redo:",
                EditAction.Cut => "cut:",
                EditAction.Copy => "copy:",
                EditAction.Paste => "paste:",
                _ => "selectAll:",
            });
            return "page";
        }
        if (FocusManager?.GetFocusedElement() is not TextBox box) return "none";
        switch (action)
        {
            case EditAction.Undo: box.Undo(); break;
            case EditAction.Redo: box.Redo(); break;
            case EditAction.Cut: box.Cut(); break;
            case EditAction.Copy: box.Copy(); break;
            case EditAction.Paste: box.Paste(); break;
            default: box.SelectAll(); break;
        }
        return "textbox";
    }

    /// <summary>--motion-check: an Edit menu item as the menu bar runs it.</summary>
    internal void TourEditMenu(string item)
    {
        _lastEditAt = default;
        RunEdit(Enum.Parse<EditAction>(item));
    }

    // ---- App menu > Settings... (Cmd+,) ----

    /// <summary>
    /// The Settings page from the menu bar, wherever the player is: the window comes forward, an open drawer closes, and
    /// the page opens the way its tab does. The opening intro, the confirm dialog and the sign-in card are modal (they
    /// keep the keyboard on Windows too): the menu leaves them be.
    /// </summary>
    internal void OpenSettingsFromMenu()
    {
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        if (Root.Children.OfType<StartupIntro>().Any() || Confirm.IsVisible || _signInPanelOpen) return;
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        if (_friendsOpen) SetFriendsOpen(false);
        ShowPage(LauncherPage.Settings);
    }

    // ---- View > Enter Full Screen (Ctrl+Cmd+F) ----

    private void FullScreen_Click(object? sender, EventArgs e) =>
        WindowState = WindowState == WindowState.FullScreen ? WindowState.Normal : WindowState.FullScreen;

    // Full screen hides the traffic lights, so the brand moves to the WPF bar's inset instead of leaving their 72 px
    // empty; the View menu's item says what it will do next.
    private void OnWindowStateChanged()
    {
        var full = WindowState == WindowState.FullScreen;
        WebsiteButton.Margin = Res.Thick(full ? "Margin.TopBarBrand.FullScreen" : "Margin.TopBarBrand");
        if (FullScreenMenuItem() is { } item) item.Header = full ? "Exit Full Screen" : "Enter Full Screen";
    }

    private NativeMenuItem? FullScreenMenuItem() =>
        NativeMenu.GetMenu(this)?.Items.OfType<NativeMenuItem>()
            .FirstOrDefault(i => i.Header == "View")?.Menu?.Items.OfType<NativeMenuItem>().FirstOrDefault();

    /// <summary>--motion-check and render shots: the brand's left inset right now.</summary>
    internal double BrandInset => WebsiteButton.Margin.Left;
}
