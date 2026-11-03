using System.Text;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Headless;
using Avalonia.Input;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.VisualTree;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.Core.Social.Launcher;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Shots;

/// <summary>
/// The Friends drawer, party and chat, the twin of the WPF UI/SocialShots.cs (`--render-shots &lt;dir&gt; --social`) under the
/// same file names: every state at 1180x740, at 980x600 and at 1600x1000, then the key scenes and Home at 100 / 125 /
/// 150 % display scale, plus keyboard-checks.txt (Tab order, Enter in chat, the Esc chain) and layout-checks.txt (the
/// toasts against Play and Join). <see cref="MainRun"/> is the default run's part: 02-06 and the DPI matrix's
/// home-friends-toasts. Made-up friends only: the social service is in demo mode (SocialService.ShowDemo), so nothing
/// signs in or touches the network, and no setting is changed.
/// </summary>
internal static class SocialShots
{
    private sealed record Scene(string Name, Action<MainWindow> Setup, Func<MainWindow, Control?>? Extra = null);

    // The smallest window the WPF launcher allows, its usual one, and one sized like a 1440p monitor's.
    private static readonly (int Width, int Height)[] Sizes = [(1180, 740), (980, 600), (1600, 1000)];

    private static string _dir = "";
    private static PopupMenu? _menu;
    // Undoes what a scene painted on a row (hover fill, focus frames): rows are reused between scenes when their content
    // is the same, so a leftover would show up in the next picture.
    private static Action? _cleanup;

    private static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static readonly SocialSnapshot SignedOut = new()
    {
        Status = SocialStatus.NotSignedIn,
        StatusMessage = "Sign in with Microsoft to use friends.",
    };

    // ---- the default run's part (WPF RenderShots: 02-06, after 01e) ----

    internal static void MainRun(MainWindow window, string outDir)
    {
        _dir = outDir;
        Step("02-06", () =>
        {
            window.TourSetFriendsOpen(true);
            Shot(window, "02-friends");
            window.TourFriendsPanel.OpenConversation(SocialDemo.Steve);
            Shot(window, "03-chat");
            window.TourFriendsPanel.OpenConversation(SocialSnapshot.PartyKey);
            Shot(window, "04-party-chat");

            window.TourFriendsPanel.HandleEscape();
            window.TourSetFriendsOpen(false);
            RenderShots.Pump();
            foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
            Shot(window, "05-toasts");
            window.TourClearToasts();

            // From here on the run shows the signed-out drawer, as the Windows run does.
            SocialHub.Service.ShowDemo(SignedOut);
            window.TourSetFriendsOpen(true);
            Shot(window, "06-signed-out");
            window.TourSetFriendsOpen(false);
            RenderShots.Pump();
        });
    }

    /// <summary>The DPI matrix's "home-friends-toasts" scene (WPF RenderShots.DpiMatrix): a signed-in player's full drawer.</summary>
    internal static void ShowHomeFriendsToasts(MainWindow window)
    {
        SocialHub.Service.ShowDemo(SocialDemo.Online(Now));
        window.TourGoTo(MainWindow.LauncherPage.Home);
        window.TourSetFriendsOpen(true);
        RenderShots.Pump();
    }

    /// <summary>Re-shown before every matrix picture (toasts time out on a real clock).</summary>
    internal static void RefreshToasts(MainWindow window)
    {
        window.TourClearToasts();
        foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
        RenderShots.Pump();
    }

    internal static void HideHomeFriendsToasts(MainWindow window)
    {
        window.TourClearToasts();
        window.TourSetFriendsOpen(false);
        SocialHub.Service.ShowDemo(SignedOut);
        RenderShots.Pump();
    }

    // ---- `--social`: the drawer in every state (WPF SocialShots.Run) ----

    internal static void Run(MainWindow window, string outDir)
    {
        _dir = outDir;
        var now = Now;
        SocialHub.Service.ShowDemo(SocialDemo.Online(now));
        window.TourGoTo(MainWindow.LauncherPage.Home);
        RenderShots.PumpFor(500);

        var scenes = Scenes(now);
        foreach (var (width, height) in Sizes)
        {
            RenderShots.Resize(window, width, height);
            window.TourSetFriendsOpen(false);
            RenderShots.Pump();
            for (var i = 0; i < scenes.Count; i++)
            {
                var scene = scenes[i];
                var name = $"{i + 1:00}-{scene.Name}-{width}x{height}";
                Step(name, () =>
                {
                    Reset(window);
                    scene.Setup(window);
                    Shot(window, name);
                    if (scene.Extra?.Invoke(window) is { } extra) SnapCrop(window, extra, name + "-popup");
                });
            }
        }
        Reset(window);
        ScaledPass(window, scenes);
        Step("keyboard checks", () => KeyboardChecks(window));
        Step("layout checks", () => ToastClearance(window));
    }

    private static void Reset(MainWindow window)
    {
        _menu?.Close();
        _menu = null;
        _cleanup?.Invoke();
        _cleanup = null;
        window.TourFriendsPanel.TourScrollListToTop();
    }

    /// <summary>
    /// Will's real drawer, then the scenes with the most icons, and Home, at 100 %, 125 % and 150 % display scale at every
    /// window size. The headless window is given that scale (layout rounds to its device pixels, text is shaped for it)
    /// and the frame is rasterised at it, so the pixels are what a scaled display shows (the WPF SetTreeDpi twin).
    /// </summary>
    private static void ScaledPass(MainWindow window, List<Scene> scenes)
    {
        string[] keep = ["will-one-offline", "list-leader", "list-long", "list-add-open", "chat-friend-online", "toasts-drawer-open"];
        var picked = scenes.Select((s, i) => (Scene: s, Index: i)).Where(x => keep.Contains(x.Scene.Name)).ToList();
        var report = new StringBuilder();
        foreach (var percent in new[] { 100, 125, 150 })
        {
            window.SetRenderScaling(percent / 100.0);
            try
            {
                foreach (var (width, height) in Sizes)
                {
                    RenderShots.Resize(window, width, height);
                    foreach (var (scene, index) in picked)
                    {
                        var name = $"{index + 1:00}-{scene.Name}-{width}x{height}-{percent}pct";
                        Step(name, () =>
                        {
                            Reset(window);
                            scene.Setup(window);
                            Shot(window, name);
                        });
                    }
                    Reset(window);
                    window.TourFriendsPanel.HandleEscape();
                    window.TourSetFriendsOpen(false);
                    window.TourClearToasts();
                    window.TourGoTo(MainWindow.LauncherPage.Home);
                    Shot(window, $"00-home-{width}x{height}-{percent}pct");
                    report.AppendLine($"{percent} % {width}x{height}: window {window.Bounds.Width:0}x{window.Bounds.Height:0} DIP, "
                        + $"render scaling {window.RenderScaling:0.##}");
                }
            }
            finally
            {
                window.SetRenderScaling(1);
                RenderShots.Pump();
            }
        }
        File.WriteAllText(Path.Combine(_dir, "scale-checks.txt"), report.ToString());
    }

    // ---- layout ---------------------------------------------------------------------

    private static Rect? BoundsIn(Control element, Visual ancestor) =>
        element.TranslatePoint(default, ancestor) is { } at ? new Rect(at, element.Bounds.Size) : null;

    /// <summary>
    /// Layout in numbers, not pictures (layout-checks.txt): with the toasts up, do they stay off Play and Join, and does
    /// the Play card (with its profile row and Join) sit under the top bar and above the bottom edge?
    /// </summary>
    private static void ToastClearance(MainWindow window)
    {
        var report = new StringBuilder();
        var profile = new ModProfile
        {
            Id = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Survival SMP", Version = "1.21.1",
            ServerName = "Survival SMP", ServerAddress = "play.example.net",
        };
        SocialHub.Service.ShowDemo(SocialDemo.Online(Now));
        window.TourSetFriendsOpen(false);
        // No profile first, at both sizes, THEN the profile: once a profile is shown, Home keeps it.
        foreach (var withProfile in new[] { false, true })
        {
            foreach (var (width, height) in new[] { (1180, 740), (980, 600) })
            {
                RenderShots.Resize(window, width, height);
                window.TourClearToasts();
                if (withProfile) window.TourShowHomeWithProfile(profile);
                else window.TourGoTo(MainWindow.LauncherPage.Home);
                RenderShots.PumpFor(300);
                foreach (var toast in SocialDemo.Toasts()) window.TourShowToast(toast);
                window.TourShowToast(new SocialToast(SocialToastKind.FriendRequest, "Friend request", "OjoTheCat wants to be friends", Uuid: SocialDemo.Ojo));
                window.TourShowToast(new SocialToast(SocialToastKind.PartyInvite, "Party invite", "Kai_Builds invited you", Uuid: SocialDemo.Kai, PartyId: "p2"));
                RenderShots.PumpFor(300);

                var label = $"{width}x{height} {(withProfile ? "with profile + Join" : "no profile")}";
                var play = window.Home.Play;
                var join = window.Home.FindControl<Button>("JoinButton") is { IsEffectivelyVisible: true } j ? j : null;
                var card = window.Home.HomeCard;
                var stack = window.Toasts.GetVisualDescendants().OfType<StackPanel>().FirstOrDefault() as Control ?? window.Toasts;
                if (BoundsIn(stack, window) is not { } stackRect || BoundsIn(play, window) is not { } playRect
                    || BoundsIn(card, window) is not { } cardRect)
                {
                    report.AppendLine($"{label}: toast stack, Play or the Home card not placed (cannot check)");
                    continue;
                }
                var pageTop = window.ThemeDouble("Size.Bar");
                report.AppendLine($"{label}: toast stack {stackRect}");
                report.AppendLine($"    Play {playRect} overlap = {stackRect.Intersects(playRect)}");
                if (join != null && BoundsIn(join, window) is { } joinRect)
                    report.AppendLine($"    Join {joinRect} overlap = {stackRect.Intersects(joinRect)} ; gap Play to Join = {joinRect.Top - playRect.Bottom:0.#} px");
                else if (withProfile)
                    report.AppendLine("    Join not visible (cannot check it)");
                var rootHeight = window.Bounds.Height;
                report.AppendLine($"    Home card {cardRect}: top clear of the top bar ({pageTop}) = {cardRect.Top >= pageTop} ; bottom above the window edge ({rootHeight}) = {cardRect.Bottom <= rootHeight}");
            }
        }
        window.TourClearToasts();
        window.TourShowHomeWithProfile(null);
        RenderShots.Resize(window, 1180, 740);
        File.WriteAllText(Path.Combine(_dir, "layout-checks.txt"), report.ToString());
    }

    // ---- keyboard ------------------------------------------------------------------

    private static void Press(MainWindow window, PhysicalKey key)
    {
        window.KeyPressQwerty(key, RawInputModifiers.None);
        window.KeyReleaseQwerty(key, RawInputModifiers.None);
        RenderShots.PumpFor(150);
    }

    /// <summary>Tab order, Enter-to-send and Esc-to-close, driven through the headless keyboard. Written to keyboard-checks.txt.</summary>
    private static void KeyboardChecks(MainWindow window)
    {
        var report = new StringBuilder();
        var panel = window.TourFriendsPanel;

        static string Describe(IInputElement? e) => e switch
        {
            null => "(none)",
            Control c => $"{c.GetType().Name} '{AutomationProperties.GetName(c)}"
                + $"{(c is ContentControl { Content: string s } ? s : "")}' [{(c.DataContext?.GetType().Name ?? "-")}]",
            _ => e.GetType().Name,
        };

        window.Activate();
        RenderShots.Resize(window, 1180, 740);
        // Tab order through the list (header, then rows).
        SocialHub.Service.ShowDemo(SocialDemo.Online(Now));
        window.TourSetFriendsOpen(true);
        RenderShots.PumpFor(300);
        panel.TourSetAddOpen(true);
        RenderShots.PumpFor(200);
        report.AppendLine("TAB ORDER (list, add box open): first focus when the drawer opens, then Tab x16:");
        // The list's scroll bar, in numbers: the list overflows here (the add box is open), so the bar must show.
        var listScroll = panel.TourListScroll;
        var bar = listScroll?.GetVisualDescendants().OfType<ScrollBar>().FirstOrDefault(b => b.Orientation == Avalonia.Layout.Orientation.Vertical);
        var thumb = bar?.GetVisualDescendants().OfType<Thumb>().FirstOrDefault();
        var fill = thumb?.GetVisualDescendants().OfType<Border>().FirstOrDefault();
        var fillText = fill != null ? $"{fill.Bounds} {fill.Background} {fill.CornerRadius} op {fill.Opacity}" : "none";
        report.Insert(0, $"LIST SCROLL: extent {listScroll?.Extent.Height:0} viewport {listScroll?.Viewport.Height:0} ; bar visible = "
            + $"{bar?.IsEffectivelyVisible} bounds {bar?.Bounds} ; thumb {thumb?.Bounds} ; thumb fill {fillText}"
            + $"{Environment.NewLine}{Environment.NewLine}");
        // WPF: panel.MoveFocus(First), what opening the drawer does.
        window.TourFocusFirstInFriends();
        RenderShots.Pump();
        for (var i = 0; i < 17; i++)
        {
            report.AppendLine($"  {i,2}: {Describe(window.FocusManager?.GetFocusedElement())}");
            Press(window, PhysicalKey.Tab);
        }

        // Enter sends: demo mode answers "Preview only.", and what was typed must stay.
        panel.TourSetAddOpen(false);
        panel.OpenConversation(SocialDemo.Steve);
        RenderShots.PumpFor(300);
        panel.TourChatInput.Text = "hello from the keyboard check";
        panel.TourChatInput.Focus();
        Press(window, PhysicalKey.Enter);
        RenderShots.PumpFor(400);
        report.AppendLine();
        report.AppendLine($"ENTER IN CHAT: input kept its text = {(panel.TourChatInput.Text?.Length ?? 0) > 0} (the demo service refuses sends), "
            + $"error line = '{panel.TourChatError.Text}' visible = {panel.TourChatError.IsEffectivelyVisible}");

        // Esc: chat -> list -> (add box) -> drawer closed.
        panel.TourChatInput.Text = "";
        panel.TourSetAddOpen(false);
        Press(window, PhysicalKey.Escape);
        var afterFirst = window.TourFriendsOpen;
        panel.TourSetAddOpen(true);
        RenderShots.PumpFor(200);
        Press(window, PhysicalKey.Escape);
        var afterAdd = window.TourFriendsOpen;
        Press(window, PhysicalKey.Escape);
        report.AppendLine();
        report.AppendLine($"ESC: chat open -> Esc -> drawer still open = {afterFirst} (expect True, back on the list)");
        report.AppendLine($"ESC: add box open -> Esc -> drawer still open = {afterAdd} (expect True, add box closed)");
        report.AppendLine($"ESC: list -> Esc -> drawer open = {window.TourFriendsOpen} (expect False)");
        window.FocusManager?.Focus(null);
        File.WriteAllText(Path.Combine(_dir, "keyboard-checks.txt"), report.ToString());
    }

    // ---- data (the WPF SocialShots builders, verbatim) ----------------------------------------------------------------

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

    private static SocialSnapshot WillsDrawer(long now) => new()
    {
        Status = SocialStatus.Online,
        HasData = true,
        Me = new SocialMe(SocialDemo.Me, "OriginPlayer", false),
        Friends = [new FriendInfo("b1".PadLeft(32, '0'), "Yozeyy", false, null, null, null, null, now - 11 * 3_600_000L)],
    };

    private static SocialSnapshot AsMember(long now) => SocialDemo.Online(now) with
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

    // ---- scenes (the WPF list, in its order, so the numbers match) ---------------------------------------------------

    private static List<Scene> Scenes(long now)
    {
        static void Show(SocialSnapshot s) => SocialHub.Service.ShowDemo(s);

        static void List(MainWindow w, SocialSnapshot s)
        {
            w.TourFriendsPanel.HandleEscape();
            w.TourFriendsPanel.TourClearNotice();
            w.TourClearToasts();
            Show(s);
            w.TourSetFriendsOpen(true);
        }

        static void Chat(MainWindow w, SocialSnapshot s, string key)
        {
            w.TourFriendsPanel.TourClearNotice();
            w.TourClearToasts();
            Show(s);
            w.TourSetFriendsOpen(true);
            w.TourFriendsPanel.HandleEscape();
            w.TourFriendsPanel.OpenConversation(key);
        }

        static Border? Bd(Button row) => row.GetVisualDescendants().OfType<Border>().FirstOrDefault(b => b.Name == "Bd");

        return
        [
            // Exactly Will's drawer from his report (2026-10-18): one friend, offline, 0 online, the add box open and
            // empty, appear offline off.
            new("will-one-offline", w =>
            {
                List(w, WillsDrawer(now));
                w.TourFriendsPanel.TourSetAddOpen(true);
            }),
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
            // Hover cannot be made without a pointer over the row, so the scene paints what the row's own
            // :pointerover style paints. Keyboard focus uses the REAL frame: the row's own FocusAdorner (Social.RowFocus)
            // built on the adorner layer over the first, a middle and the last row of a card.
            new("row-hover", w =>
            {
                List(w, NoParty(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump();
                if (w.TourFriendsPanel.TourFindRow(SocialDemo.Kai) is { } row && Bd(row) is { } bd)
                {
                    bd.Background = Res.Brush("Brush.CardAlt");
                    _cleanup = () => bd.ClearValue(Border.BackgroundProperty);
                }
            }),
            new("row-focus", w =>
            {
                List(w, NoParty(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump();
                var added = new List<(AdornerLayer Layer, Control Frame)>();
                foreach (var uuid in new[] { SocialDemo.Steve, SocialDemo.Alex, SocialDemo.Rin })
                {
                    if (w.TourFriendsPanel.TourFindRow(uuid) is not { } row) continue;
                    if (AdornerLayer.GetAdornerLayer(row) is not { } layer || row.FocusAdorner?.Build() is not { } frame) continue;
                    frame.IsHitTestVisible = false;
                    AdornerLayer.SetAdornedElement(frame, row);
                    layer.Children.Add(frame);
                    added.Add((layer, frame));
                }
                _cleanup = () => { foreach (var (layer, frame) in added) layer.Children.Remove(frame); };
            }, w => w),
            new("list-notice", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.ShowNotice("Can't join: Steve is not on a server.", error: true);
            }),
            new("list-long-scrolled-to-end", w =>
            {
                List(w, LongList(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump();
                w.TourFriendsPanel.TourScrollListToEnd();
            }),
            new("menu-friend-list", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump();
                _menu = w.TourFriendsPanel.TourOpenFriendMenu(SocialDemo.Steve, inChat: false);
            }, _ => _menu?.Card),
            new("menu-party-member", w =>
            {
                List(w, SocialDemo.Online(now));
                w.TourFriendsPanel.TourSetAddOpen(false);
                RenderShots.Pump();
                _menu = w.TourFriendsPanel.TourOpenMemberMenu(SocialDemo.Alex);
            }, _ => _menu?.Card),
            new("menu-friend-chat", w =>
            {
                Chat(w, SocialDemo.Online(now), SocialDemo.Steve);
                RenderShots.Pump();
                _menu = w.TourFriendsPanel.TourOpenFriendMenu(SocialDemo.Steve, inChat: true);
            }, _ => _menu?.Card),
            new("chat-near-limit", w =>
            {
                Chat(w, SocialDemo.Online(now), SocialDemo.Steve);
                w.TourFriendsPanel.TourChatInput.Text = new string('x', 236);
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
                Show(SignedOut);
                w.TourSetFriendsOpen(true);
            }),
            new("state-connecting", _ => Show(new SocialSnapshot { Status = SocialStatus.Starting })),
            new("state-unreachable", _ => Show(new SocialSnapshot
            {
                Status = SocialStatus.Unreachable,
                StatusMessage = "Origin can't reach its friends service. It keeps trying in the background.",
            })),
            new("state-sign-in-failed", _ => Show(new SocialSnapshot
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

    // ---- pictures ----

    // The theme's short transitions (hover colours, the toast entrance) run on the clock even with motion off: let real
    // time pass so each picture shows the settled state.
    private static void Shot(MainWindow window, string name)
    {
        RenderShots.PumpFor(300);
        RenderShots.Snap(window, name);
    }

    // The WPF "-popup" picture is the pop-up drawn on its own. The Mac draws the menu on the window's overlay layer, so it
    // is cut from the window's frame, drawn at 2x like the WPF pop-up pictures: the card's bounds plus Popup.Bleed (the
    // WPF pop-up's shadow margin), so the two pictures line up 1:1. For row-focus the element is the window itself
    // (the WPF picture is the adorner layer, the whole window).
    private static void SnapCrop(MainWindow window, Control element, string name)
    {
        const double Zoom = 2;
        window.SetRenderScaling(Zoom);
        try
        {
            RenderShots.Pump();
            using var back = window.CaptureRenderedFrame();
            if (back == null || element.TranslatePoint(default, window) is not { } at)
            {
                RenderShots.Fail(name + " (not on screen)");
                return;
            }
            var pad = ReferenceEquals(element, window) ? default : Res.Thick("Popup.Bleed");
            var src = new Rect(at.X - pad.Left, at.Y - pad.Top, element.Bounds.Width + pad.Left + pad.Right,
                element.Bounds.Height + pad.Top + pad.Bottom).Intersect(new Rect(window.Bounds.Size));
            // DrawImage's source rect is in the frame's pixels: map the window's DIP rect onto them.
            var k = back.PixelSize.Width / window.Bounds.Width;
            var from = new Rect(src.X * k, src.Y * k, src.Width * k, src.Height * k);
            using var target = new RenderTargetBitmap(new PixelSize((int)Math.Ceiling(src.Width * Zoom), (int)Math.Ceiling(src.Height * Zoom)));
            using (var dc = target.CreateDrawingContext())
                dc.DrawImage(back, from, new Rect(0, 0, src.Width * Zoom, src.Height * Zoom));
            target.Save(Path.Combine(_dir, name + ".png"));
            RenderShots.Count();
        }
        finally
        {
            window.SetRenderScaling(1);
            RenderShots.Pump();
        }
    }

    private static void Step(string what, Action body)
    {
        try
        {
            body();
        }
        catch (Exception ex)
        {
            File.AppendAllText(Path.Combine(_dir, "social-error.txt"), $"{what}: {ex}{Environment.NewLine}");
            RenderShots.Fail($"{what} ({ex.GetType().Name}: {ex.Message})");
        }
    }
}
