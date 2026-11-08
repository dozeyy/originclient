using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Mac.UI;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac;

// Origin social (docs/SOCIAL.md): the Friends button, its drawer, and the social toasts' actions. The twin of the WPF
// MainWindow's "Origin social" region: the same Core/Social service (SocialHub.Service), the same rules.
public sealed partial class MainWindow
{
    private readonly FriendsPanel _friendsPanel = new();
    private readonly TranslateTransform _friendsShift = new();
    private bool _friendsOpen;

    private void InitSocial()
    {
        FriendsDrawer.RenderTransform = _friendsShift;
        _friendsShift.X = UI.Res.D("Size.Drawer");
        FriendsPanelHost.Content = _friendsPanel;

        _friendsPanel.CloseRequested += (_, _) => SetFriendsOpen(false);
        _friendsPanel.AddAccountRequested += (_, _) =>
        {
            SetFriendsOpen(false);
            SetAccountPanelOpen(true);
        };
        _friendsPanel.JoinRequested += request => _ = JoinAsync(request);
        FriendsButton.Click += (_, _) => SetFriendsOpen(!_friendsOpen);

        Toasts.Opened += OnToastOpened;
        Toasts.MoreClicked += () => SetFriendsOpen(true);
        Toasts.ActionClicked += toast => _ = OnToastActionAsync(toast);

        var social = SocialHub.Service;
        social.Changed += snapshot => Dispatcher.UIThread.Post(() => ApplySocial(snapshot));
        social.ToastRaised += toast => Dispatcher.UIThread.Post(() => ShowSocialToast(toast));
        ApplySocial(social.Snapshot);

        // Esc inside the drawer steps back (menu, confirm, add box, chat) before it closes the drawer. Registered after
        // the Accounts and Import handlers, which take Esc first when their own surface is up.
        AddHandler(KeyDownEvent, (_, e) =>
        {
            if (e.Key != Key.Escape || !_friendsOpen) return;
            if (!_friendsPanel.HandleEscape()) SetFriendsOpen(false);
            e.Handled = true;
        }, RoutingStrategies.Tunnel);

        // A render-shots run shows made-up friends only (SocialService.ShowDemo): it never signs in or syncs.
        if (OriginPaths.IsScratchRoot) return;
        // Sign-in and the first sync start once the window is on screen, all on the thread pool: never on the
        // cold-start path.
        Opened += (_, _) => social.Start();
        // The sync slows to once a minute after 5 minutes in the background.
        Activated += (_, _) => social.SetAppActive(true);
        Deactivated += (_, _) => social.SetAppActive(false);
        PropertyChanged += (_, e) =>
        {
            if (e.Property == WindowStateProperty && WindowState == WindowState.Minimized) social.SetAppActive(false);
        };
    }

    private void ApplySocial(SocialSnapshot snapshot)
    {
        _friendsPanel.Apply(snapshot);
        var attention = snapshot.AttentionCount;
        SetFriendsBadge(attention > 0);
        ToolTip.SetTip(FriendsButton, attention > 0 ? $"Friends - {attention} new" : "Friends");
    }

    private void ShowSocialToast(SocialToast toast)
    {
        // Requests and invites are right there in an open drawer.
        if (_friendsOpen && toast.Kind is SocialToastKind.FriendRequest or SocialToastKind.PartyInvite) return;
        Toasts.Show(toast);
    }

    private void OnToastOpened(SocialToast toast)
    {
        SetFriendsOpen(true);
        if (toast.Kind == SocialToastKind.Message && toast.Uuid != null)
            _friendsPanel.OpenConversation(toast.Uuid);
    }

    private async Task OnToastActionAsync(SocialToast toast)
    {
        var social = SocialHub.Service;
        switch (toast.Kind)
        {
            case SocialToastKind.Warp:
                var leader = toast.Uuid != null ? social.Snapshot.NameOf(toast.Uuid) : "your party leader";
                await JoinAsync(new JoinRequest(leader, toast.Version, toast.Server));
                break;
            case SocialToastKind.PartyInvite when toast.PartyId != null:
                ReportToastAction(await social.AcceptPartyAsync(toast.PartyId));
                break;
            case SocialToastKind.FriendRequest when toast.Uuid != null:
                ReportToastAction(await social.RespondAsync(toast.Uuid, true));
                break;
        }
    }

    private void ReportToastAction(SocialActionResult result)
    {
        if (result.Ok || string.IsNullOrEmpty(result.Error)) return;
        SetFriendsOpen(true);
        _friendsPanel.ShowNotice(result.Error, error: true);
    }

    /// <summary>
    /// Join a friend's (or the party leader's) server: launch THEIR Minecraft version straight into it, through Home's
    /// normal launch path. A version Origin doesn't ship is explained instead.
    /// </summary>
    private async Task JoinAsync(JoinRequest request)
    {
        var block = JoinArguments.Check(request.Version, request.Server, VersionCatalog.IsSupported);
        if (block != JoinBlock.None)
        {
            var why = JoinArguments.Explain(block, request.Who, request.Version);
            if (_friendsOpen) _friendsPanel.ShowNotice(why, error: true);
            else Toasts.Show(new SocialToast(SocialToastKind.Notice, "Can't join", why));
            return;
        }

        if (_signInPanelOpen) CloseSignInPanel();
        if (_accountPanelOpen) SetAccountPanelOpen(false);
        if (_friendsOpen) SetFriendsOpen(false);
        ShowPage(LauncherPage.Home);
        if (_currentPage != LauncherPage.Home || _importOpen) return;
        await Home.JoinServerAsync(request.Version!, request.Server!);
    }

    private void SetFriendsOpen(bool open)
    {
        if (open == _friendsOpen) return;
        if (open && (_signInPanelOpen || _importOpen)) return; // finish (or cancel) signing in or importing first
        if (open && _accountPanelOpen) SetAccountPanelOpen(false);

        _friendsOpen = open;
        SocialHub.Service.SetDrawerOpen(open);
        _friendsPanel.SetShown(open);
        PlaceToasts();

        var ms = (int)(open ? UI.Res.Get<TimeSpan>("Motion.Scene") : UI.Res.Get<TimeSpan>("Motion.Exit")).TotalMilliseconds;
        if (open)
        {
            Scrim.IsVisible = true;
            FriendsDrawer.IsVisible = true;
        }
        FriendsDrawer.IsHitTestVisible = open;
        Scrim.IsHitTestVisible = open;
        MotionFx.Run(Scrim, OpacityProperty, Scrim.Opacity, open ? 0.5 : 0, ms);
        MotionFx.Run(FriendsDrawer, _friendsShift, TranslateTransform.XProperty, _friendsShift.X, open ? 0 : UI.Res.D("Size.Drawer"), ms)
            .ContinueWith(_ => Dispatcher.UIThread.Post(() =>
            {
                if (_friendsOpen) return;
                FriendsDrawer.IsVisible = false;
                if (!_accountPanelOpen && !_signInPanelOpen) Scrim.IsVisible = false;
            }));

        if (open) Dispatcher.UIThread.Post(FocusFirstInFriends, DispatcherPriority.Input);
    }

    // WPF: FriendsPanelHost.MoveFocus(First). The drawer's first control that can take the keyboard.
    private void FocusFirstInFriends()
    {
        if (!_friendsOpen) return;
        _friendsPanel.GetVisualDescendants().OfType<InputElement>()
            .FirstOrDefault(e => e.Focusable && e.IsEffectivelyVisible && e.IsEffectivelyEnabled && e is not TextBox { IsVisible: false })
            ?.Focus();
    }

    // ---- render shots (UI/Shots/SocialShots.cs): made-up friends, the demo service, no network ----

    internal FriendsPanel TourFriendsPanel => _friendsPanel;

    internal bool TourFriendsOpen => _friendsOpen;

    internal void TourSetFriendsOpen(bool open) => SetFriendsOpen(open);

    internal void TourFocusFirstInFriends() => FocusFirstInFriends();

    internal Control TourFriendsDrawer => FriendsDrawer;
}
