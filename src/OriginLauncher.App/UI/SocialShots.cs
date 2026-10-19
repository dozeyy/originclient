using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI;

/// <summary>
/// `OriginLauncher.App.exe --render-shots &lt;folder&gt; --social` renders ONLY the
/// Friends / party / chat surfaces, in every state, at 1180x740 and at the
/// smallest window the launcher allows (980x600). Made-up data only (the
/// social service is in demo mode, nothing touches the network) and no setting
/// is ever changed, so it can run on a PC with a real launcher install.
/// </summary>
public static class SocialShots
{
    // Extra: an element (a pop-up menu) to save as its own picture after the window's.
    private sealed record Scene(string Name, Action<MainWindow> Setup, Func<FrameworkElement?>? Extra = null);

    private static ContextMenu? _menu;
    // Undoes what a scene painted on a row (hover fill, focus frame): rows are reused between scenes
    // when their content is the same, so a leftover would show up in the next picture.
    private static Action? _cleanup;
    // A picture of something the window picture cannot hold (a pop-up menu, the focus-frame adorner layer).
    private static FrameworkElement? _extraElement;

    public static void Run(string outDir)
    {
        Directory.CreateDirectory(outDir);
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        SocialHub.OnlineCountOverride = 1284;

        var window = new MainWindow
        {
            ShowActivated = false,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Left = 0,
            Top = 0,
        };
        SocialHub.Service.ShowDemo(SocialDemo.Online(now));
        window.Show();
        RenderShots.Pump(2500);

        var scenes = Scenes(now);
        foreach (var (width, height) in new[] { (1180, 740), (980, 600) })
        {
            window.Width = width;
            window.Height = height;
            window.TourSetFriendsOpen(false);
            RenderShots.Pump(500);
            for (var i = 0; i < scenes.Count; i++)
            {
                var scene = scenes[i];
                try
                {
                    if (_menu != null) _menu.IsOpen = false;
                    _menu = null;
                    _cleanup?.Invoke();
                    _cleanup = null;
                    _extraElement = null;
                    window.TourFriendsPanel.TourScrollListToTop();
                    scene.Setup(window);
                    RenderShots.Pump(450);
                    var name = $"{i + 1:00}-{scene.Name}-{width}x{height}";
                    RenderShots.Snap(window, outDir, name);
                    if (scene.Extra?.Invoke() is { } extra) SnapElement(extra, outDir, name + "-popup");
                }
                catch (Exception ex)
                {
                    File.AppendAllText(Path.Combine(outDir, "social-error.txt"), $"{scene.Name}: {ex}\n");
                }
            }
        }
        if (_menu != null) _menu.IsOpen = false;
        _cleanup?.Invoke();
        _cleanup = null;
        KeyboardChecks(window, outDir);
        ToastClearance(window, outDir);
        window.Close();
    }

    private static T? FindAncestor<T>(DependencyObject start) where T : DependencyObject
    {
        for (var d = VisualTreeHelper.GetParent(start); d != null; d = VisualTreeHelper.GetParent(d))
            if (d is T t) return t;
        return null;
    }

    /// <summary>
    /// Draws a row's focus frame the way WPF does when you Tab to it: a control styled with the
    /// row's own FocusVisualStyle, laid over the row on the adorner layer.
    /// </summary>
    private sealed class FocusPreview : Adorner
    {
        private readonly Control _frame;

        public FocusPreview(FrameworkElement adorned) : base(adorned)
        {
            IsHitTestVisible = false;
            _frame = new Control { Style = adorned.FocusVisualStyle, IsHitTestVisible = false };
            AddVisualChild(_frame);
        }

        protected override int VisualChildrenCount => 1;

        protected override Visual GetVisualChild(int index) => _frame;

        protected override Size MeasureOverride(Size constraint)
        {
            _frame.Measure(AdornedElement.RenderSize);
            return AdornedElement.RenderSize;
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            _frame.Arrange(new Rect(AdornedElement.RenderSize));
            return AdornedElement.RenderSize;
        }
    }

    private static void SnapElement(FrameworkElement element, string dir, string name)
    {
        var width = Math.Max(1, (int)Math.Ceiling(element.ActualWidth));
        var height = Math.Max(1, (int)Math.Ceiling(element.ActualHeight));
        var bitmap = new RenderTargetBitmap(width * 2, height * 2, 192, 192, PixelFormats.Pbgra32);
        bitmap.Render(element);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(Path.Combine(dir, name + ".png"));
        encoder.Save(file);
    }

    // ---- layout ---------------------------------------------------------------------

    private static Rect BoundsIn(FrameworkElement element, Visual ancestor) =>
        element.TransformToAncestor(ancestor).TransformBounds(new Rect(0, 0, element.ActualWidth, element.ActualHeight));

    private static T? FindVisual<T>(DependencyObject parent, Func<T, bool> match) where T : DependencyObject
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T t && match(t)) return t;
            if (FindVisual(child, match) is { } found) return found;
        }
        return default;
    }

    /// <summary>
    /// Layout in numbers, not pictures (layout-checks.txt): with the toasts up, do they stay off Play and Join,
    /// and does the Play card (with its profile row and Join) sit under the top bar and above the bottom edge?
    /// </summary>
    private static void ToastClearance(MainWindow window, string outDir)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var report = new StringBuilder();
        var profile = new Core.Profiles.ModProfile
        {
            Id = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Survival SMP", Version = "1.21.1",
            ServerName = "Survival SMP", ServerAddress = "play.example.net",
        };
        SocialHub.Service.ShowDemo(SocialDemo.Online(now));
        window.TourSetFriendsOpen(false);
        // No profile first, at both sizes, THEN the profile: once a profile is shown, Home keeps it.
        foreach (var withProfile in new[] { false, true })
        {
            foreach (var (width, height) in new[] { (1180, 740), (980, 600) })
            {
                window.Width = width;
                window.Height = height;
                window.TourClearToasts();
                if (withProfile) window.TourShowHomeWithProfile(profile);
                else window.TourGoTo(0);
                RenderShots.Pump(600);
                foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
                window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
                window.TourShowToast(new SocialToast(SocialToastKind.PartyInvite, "Party invite", "Kai_Builds invited you", Uuid: SocialDemo.Kai, PartyId: "p2"));
                RenderShots.Pump(600);
                window.UpdateLayout();

                var label = $"{width}x{height} {(withProfile ? "with profile + Join" : "no profile")}";
                var root = (Visual)window.Content;
                var play = FindVisual<Button>(root, b => ReferenceEquals(b.Style, Application.Current.FindResource("Button.PlayHero")));
                var join = FindVisual<Button>(root, b => b.Name == "JoinButton" && b.IsVisible);
                var card = FindVisual<StackPanel>(root, p => p.Name == "HomeCard");
                var toasts = FindVisual<SocialToastHost>(root, _ => true);
                if (play == null || toasts == null || card == null)
                {
                    report.AppendLine($"{label}: play button found = {play != null}, card found = {card != null}, toast host found = {toasts != null} (cannot check)");
                    continue;
                }
                var stack = BoundsIn(toasts, root);
                var playRect = BoundsIn(play, root);
                var cardRect = BoundsIn(card, root);
                var pageTop = window.FindName("PageHost") is FrameworkElement host ? host.Margin.Top : 58;
                report.AppendLine($"{label}: toast stack {stack}");
                report.AppendLine($"    Play {playRect} overlap = {stack.IntersectsWith(playRect)}");
                if (join != null)
                {
                    var joinRect = BoundsIn(join, root);
                    report.AppendLine($"    Join {joinRect} overlap = {stack.IntersectsWith(joinRect)} ; gap Play to Join = {joinRect.Top - playRect.Bottom:0.#} px");
                }
                else if (withProfile)
                {
                    report.AppendLine("    Join not visible (cannot check it)");
                }
                var rootHeight = ((FrameworkElement)window.Content).ActualHeight;
                report.AppendLine($"    Home card {cardRect}: top clear of the top bar ({pageTop}) = {cardRect.Top >= pageTop} ; bottom above the window edge ({rootHeight}) = {cardRect.Bottom <= rootHeight}");
            }
        }
        window.TourClearToasts();
        File.WriteAllText(Path.Combine(outDir, "layout-checks.txt"), report.ToString());
    }

    // ---- keyboard ------------------------------------------------------------------

    private static void Press(UIElement target, Key key, bool preview = false)
    {
        var source = PresentationSource.FromVisual(target)!;
        target.RaiseEvent(new KeyEventArgs(Keyboard.PrimaryDevice, source, Environment.TickCount, key)
        {
            RoutedEvent = preview ? Keyboard.PreviewKeyDownEvent : Keyboard.KeyDownEvent,
        });
    }

    /// <summary>Tab order, Enter-to-send and Esc-to-close, driven through the real key events. Written to keyboard-checks.txt.</summary>
    private static void KeyboardChecks(MainWindow window, string outDir)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var report = new StringBuilder();
        var panel = window.TourFriendsPanel;

        string Describe(IInputElement? e) => e switch
        {
            null => "(none)",
            FrameworkElement fe => $"{fe.GetType().Name} '{System.Windows.Automation.AutomationProperties.GetName(fe)}"
                + $"{(fe is ContentControl { Content: string c } ? c : "")}' [{(fe.DataContext?.GetType().Name ?? "-")}]",
            _ => e.GetType().Name,
        };

        // Tab order through the list (header, then rows).
        SocialHub.Service.ShowDemo(SocialDemo.Online(now));
        window.TourSetFriendsOpen(true);
        RenderShots.Pump(500);
        panel.TourSetAddOpen(true);
        RenderShots.Pump(200);
        report.AppendLine("TAB ORDER (list, add box open): first focus when the drawer opens, then Tab x16:");
        panel.MoveFocus(new TraversalRequest(FocusNavigationDirection.First));
        for (var i = 0; i < 17; i++)
        {
            report.AppendLine($"  {i,2}: {Describe(Keyboard.FocusedElement)}");
            (Keyboard.FocusedElement as FrameworkElement)?.MoveFocus(new TraversalRequest(FocusNavigationDirection.Next));
        }

        // Enter sends: demo mode answers "Preview only.", and what was typed must stay.
        panel.TourSetAddOpen(false);
        panel.OpenConversation(SocialDemo.Steve);
        RenderShots.Pump(400);
        panel.ChatInput.Text = "hello from the keyboard check";
        panel.ChatInput.Focus();
        Press(panel.ChatInput, Key.Enter);
        RenderShots.Pump(600);
        report.AppendLine();
        report.AppendLine($"ENTER IN CHAT: input kept its text = {panel.ChatInput.Text.Length > 0} (the demo service refuses sends), "
            + $"error line = '{panel.ChatError.Text}' visible = {panel.ChatError.Visibility}");

        // Esc: chat -> list -> (add box) -> drawer closed.
        panel.ChatInput.Text = "";
        panel.TourSetAddOpen(false);
        Press(window, Key.Escape, preview: true);
        RenderShots.Pump(300);
        var afterFirst = window.TourFriendsOpen;
        panel.TourSetAddOpen(true);
        RenderShots.Pump(200);
        Press(window, Key.Escape, preview: true);
        RenderShots.Pump(300);
        var afterAdd = window.TourFriendsOpen;
        Press(window, Key.Escape, preview: true);
        RenderShots.Pump(400);
        report.AppendLine();
        report.AppendLine($"ESC: chat open -> Esc -> drawer still open = {afterFirst} (expect True, back on the list)");
        report.AppendLine($"ESC: add box open -> Esc -> drawer still open = {afterAdd} (expect True, add box closed)");
        report.AppendLine($"ESC: list -> Esc -> drawer open = {window.TourFriendsOpen} (expect False)");
        File.WriteAllText(Path.Combine(outDir, "keyboard-checks.txt"), report.ToString());
    }

    // ---- data -----------------------------------------------------------------------

    private static SocialSnapshot Empty(long now) => new()
    {
        Status = SocialStatus.Online,
        HasData = true,
        Me = new SocialMe(SocialDemo.Me, "OriginPlayer", false),
    };

    private static SocialSnapshot LongList(long now)
    {
        string[] names =
        [
            "Steve", "Alex", "Kai_Builds", "rinrin", "Notch", "Dream_SMP_fan_1", "xX_Slayer_Xx", "Technoblade_RIP",
            "ender_pearl", "CreeperAwMan", "redstone_guy", "Herobrine", "jeb_", "Dinnerbone", "Grumm", "MumboJumbo",
            "iskall85", "Xisuma", "Etho", "BdoubleO100", "Grian", "Scar", "Pearl", "Impulse", "Tango", "Zedaph",
            "Keralis", "Cubfan135", "Docm77", "Welsknight",
        ];
        string?[] servers = ["hypixel.net", "play.cubecraft.net", "mc.very-long-server-name-network.example.com", null, "mineplex.com"];
        var friends = new List<FriendInfo>();
        for (var i = 0; i < names.Length; i++)
        {
            var uuid = (0x100 + i).ToString("x32");
            var online = i % 3 != 2;
            var server = servers[i % servers.Length];
            friends.Add(online
                ? new FriendInfo(uuid, names[i], true, i % 4 == 3 ? "launcher" : "game",
                    server != null ? "server" : "singleplayer", server, i % 2 == 0 ? "1.21.1" : "1.20.1", now)
                : new FriendInfo(uuid, names[i], false, null, null, null, null, now - (i + 1) * 3_600_000L));
        }
        return SocialDemo.Online(now) with
        {
            Friends = friends.OrderByDescending(f => f.Online).ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase).ToList(),
            Incoming =
            [
                new FriendRequest("a1".PadLeft(32, '0'), "OjoTheCat", now),
                new FriendRequest("a2".PadLeft(32, '0'), "A_Very_Long_Name_", now),
            ],
        };
    }

    private static SocialSnapshot AsMember(long now)
    {
        var s = SocialDemo.Online(now);
        return s with
        {
            Party = new PartyInfo("p1", SocialDemo.Steve,
            [
                new PartyMember(SocialDemo.Steve, "Steve", true),
                new PartyMember(SocialDemo.Me, "OriginPlayer", true),
                new PartyMember(SocialDemo.Alex, "Alex", true),
                new PartyMember(SocialDemo.Notch, "Notch", false),
            ]),
            PartyInvites = [],
            Incoming = [],
            Outgoing = [],
        };
    }

    private static SocialSnapshot NoParty(long now) => SocialDemo.Online(now) with
    {
        Party = null,
        PartyInvites = [],
        PartyChat = [],
        UnreadParty = 0,
        Incoming = [],
        Outgoing = [],
        Unread = new Dictionary<string, int>(),
    };

    private static SocialSnapshot WithWarp(long now) => SocialDemo.Online(now) with
    {
        Warp = new WarpRequest("hypixel.net", "1.21.1", now, SocialDemo.Steve, "Steve"),
        Me = new SocialMe(SocialDemo.Me, "OriginPlayer", true),
    };

    private static SocialSnapshot LongChat(long now)
    {
        long Ago(int minutes) => now - minutes * 60_000L;
        ChatMessage Dm(string id, string from, string to, string text, int minutesAgo) =>
            new(id, from, to, text, Ago(minutesAgo));
        const string me = SocialDemo.Me, steve = SocialDemo.Steve;
        var lines = new List<ChatMessage>
        {
            Dm("c1", steve, me, "yo, did you see the new update?", 3200),
            Dm("c2", me, steve, "not yet, whats in it", 3190),
            Dm("c3", steve, me, "new shaders and a faster launcher and the new capes are in the store", 3188),
            Dm("c4", steve, me, "also the map thing", 3187),
            Dm("c5", me, steve, "nice", 300),
            Dm("c6", steve, me, "you on tonight?", 42),
            Dm("c7", steve, me, "bed wars? I'm on hypixel already. bring the whole party, we need four for the new map and the queue is empty right now so we should get in fast, then we can try the new mode after", 41),
            Dm("c8", me, steve, "yeah, after dinner", 40),
            Dm("c9", me, steve, "give me twenty minutes", 40),
            Dm("c10", me, steve, "https://example.com/a/really/long/link/with/no/spaces/that/should/still/wrap/inside/the/bubble/without/clipping", 39),
            Dm("c11", steve, me, "ok", 3),
            Dm("c12", steve, me, "hit join on my name in the launcher", 1),
        };
        return SocialDemo.Online(now) with
        {
            Conversations = new Dictionary<string, IReadOnlyList<ChatMessage>> { [steve] = lines },
            Unread = new Dictionary<string, int>(),
            UnreadParty = 0,
        };
    }

    // ---- scenes ---------------------------------------------------------------------

    private static List<Scene> Scenes(long now)
    {
        static void Show(SocialSnapshot s) => SocialHub.Service.ShowDemo(s);

        void List(MainWindow w, SocialSnapshot s)
        {
            w.TourFriendsPanel.HandleEscape();
            w.TourFriendsPanel.TourClearNotice();
            w.TourClearToasts();
            Show(s);
            w.TourSetFriendsOpen(true);
        }

        void Chat(MainWindow w, SocialSnapshot s, string key)
        {
            w.TourFriendsPanel.TourClearNotice();
            w.TourClearToasts();
            Show(s);
            w.TourSetFriendsOpen(true);
            w.TourFriendsPanel.HandleEscape();
            w.TourFriendsPanel.OpenConversation(key);
        }

        static Border? Bd(Button row) => row.Template.FindName("Bd", row) as Border;

        static void OpenAt(MainWindow w, ContextMenu menu, double x, double y)
        {
            menu.PlacementTarget = w;
            menu.Placement = PlacementMode.AbsolutePoint;
            menu.HorizontalOffset = w.Left + x;
            menu.VerticalOffset = w.Top + y;
            menu.IsOpen = true;
        }

        return
        [
            new("list-leader", w => List(w, SocialDemo.Online(now))),
            new("list-member", w => List(w, AsMember(now))),
            new("list-no-party", w => List(w, NoParty(now))),
            new("list-empty", w => List(w, Empty(now))),
            new("list-long", w => List(w, LongList(now))),
            new("list-warp-appear-offline", w => List(w, WithWarp(now))),
            new("list-add-open", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.TourSetAddOpen(true);
                w.TourFriendsPanel.ShowNotice("Friend request sent to dinnerbone.", error: false);
            }),
            new("list-add-error", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.TourShowAddError("That isn't a Minecraft username.");
            }),
            // Hover cannot be made by an offscreen run, so that scene paints what the row's own trigger
            // paints (Brush.CardAlt). Keyboard focus uses the REAL frame: the row's own FocusVisualStyle
            // (Social.RowFocus) on a preview adorner, on the first, a middle and the last row of a card.
            new("row-hover", w =>
            {
                List(w, NoParty(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump(250);
                if (w.TourFriendsPanel.TourFindRow(SocialDemo.Kai) is { } row && Bd(row) is { } bd)
                {
                    bd.Background = (Brush)Application.Current.FindResource("Brush.CardAlt");
                    _cleanup = () => bd.ClearValue(Border.BackgroundProperty);
                }
            }),
            new("row-focus", w =>
            {
                List(w, NoParty(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump(250);
                var added = new List<(AdornerLayer Layer, Adorner Adorner)>();
                foreach (var uuid in new[] { SocialDemo.Steve, SocialDemo.Alex, SocialDemo.Rin })
                {
                    if (w.TourFriendsPanel.TourFindRow(uuid) is not { } row) continue;
                    if (AdornerLayer.GetAdornerLayer(row) is not { } layer) continue;
                    var adorner = new FocusPreview(row);
                    layer.Add(adorner);
                    added.Add((layer, adorner));
                }
                _cleanup = () => { foreach (var (layer, adorner) in added) layer.Remove(adorner); };
                // The adorner layer sits above the window's content, so the window picture cannot hold it.
                _extraElement = FindAncestor<AdornerDecorator>((DependencyObject)w.Content);
            }, () => _extraElement),
            new("list-notice", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.ShowNotice("Can't join: Steve is not on a server.", error: true);
            }),
            new("list-long-scrolled-to-end", w =>
            {
                List(w, LongList(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump(300);
                w.TourFriendsPanel.TourScrollListToEnd();
            }),
            new("menu-friend-list", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump(200);
                _menu = w.TourFriendsPanel.TourFriendMenu(SocialDemo.Steve, inChat: false);
                OpenAt(w, _menu!, w.ActualWidth - 330, 300);
            }, () => _menu),
            new("menu-party-member", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump(200);
                _menu = w.TourFriendsPanel.TourMemberMenu(SocialDemo.Alex);
                OpenAt(w, _menu, w.ActualWidth - 330, 300);
            }, () => _menu),
            new("menu-friend-chat", w =>
            {
                Chat(w, SocialDemo.Online(now), SocialDemo.Steve);
                RenderShots.Pump(200);
                _menu = w.TourFriendsPanel.TourFriendMenu(SocialDemo.Steve, inChat: true);
                OpenAt(w, _menu!, w.ActualWidth - 230, 52);
            }, () => _menu),
            new("chat-near-limit", w =>
            {
                Chat(w, SocialDemo.Online(now), SocialDemo.Steve);
                w.TourFriendsPanel.ChatInput.Text = new string('x', 236);
            }),
            new("list-stale", w => List(w, SocialDemo.Online(now) with { Status = SocialStatus.Unreachable })),
            new("chat-friend-online", w => Chat(w, SocialDemo.Online(now), SocialDemo.Steve)),
            new("chat-friend-long", w => Chat(w, LongChat(now), SocialDemo.Steve)),
            new("chat-friend-offline-empty", w => Chat(w, SocialDemo.Online(now), SocialDemo.Notch)),
            new("chat-party", w => Chat(w, SocialDemo.Online(now), SocialSnapshot.PartyKey)),
            new("chat-party-alone", w => Chat(w, SocialDemo.Online(now) with
            {
                Party = new PartyInfo("p1", SocialDemo.Me, [new PartyMember(SocialDemo.Me, "OriginPlayer", true)]),
                PartyChat = [],
                UnreadParty = 0,
            }, SocialSnapshot.PartyKey)),
            new("confirm", w =>
            {
                Chat(w, SocialDemo.Online(now), SocialDemo.Steve);
                _ = w.TourFriendsPanel.TourShowConfirm();
            }),
            new("state-signed-out", w =>
            {
                w.TourFriendsPanel.TourDismissConfirm();
                w.TourFriendsPanel.HandleEscape();
                Show(new SocialSnapshot { Status = SocialStatus.NotSignedIn, StatusMessage = "Sign in with Microsoft to use friends." });
                w.TourSetFriendsOpen(true);
            }),
            new("state-connecting", w => Show(new SocialSnapshot { Status = SocialStatus.Starting })),
            new("state-unreachable", w => Show(new SocialSnapshot
            {
                Status = SocialStatus.Unreachable,
                StatusMessage = "Origin can't reach its friends service. It keeps trying in the background.",
            })),
            new("state-sign-in-failed", w => Show(new SocialSnapshot
            {
                Status = SocialStatus.SignInFailed,
                StatusMessage = "Microsoft said this Minecraft account has no profile.",
            })),
            new("toasts", w =>
            {
                w.TourSetFriendsOpen(false);
                Show(SocialDemo.Online(now));
                foreach (var toast in SocialDemo.Toasts()) w.TourShowToast(toast);
                w.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
                w.TourShowToast(new SocialToast(SocialToastKind.PartyInvite, "Party invite", "Kai_Builds invited you to their party", Uuid: SocialDemo.Kai, PartyId: "p2"));
            }),
            new("toasts-drawer-open", w =>
            {
                w.TourClearToasts();
                List(w, SocialDemo.Online(now));
                foreach (var toast in SocialDemo.Toasts()) w.TourShowToast(toast);
            }),
        ];
    }
}
