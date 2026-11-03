using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.VisualTree;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Pages;

// Mod profiles (port of the WPF UI/Pages/ProfilesPage): named setups, each with its own mods folder for one version
// and an optional server to join. Cards, not rows: a profile should feel like a setup you own (emblem, name, who it
// plays as, its mods, where it goes), with the version only a small tag. Picking a card records which profile Play
// uses (one LauncherState.Write, so Home follows through SettingsChanged). New and Edit open the form as a dialog;
// Delete asks inline on the card (Delete / Cancel), like the Mods page's Remove.
public sealed class ProfilesPage : Panel, IPage
{
    /// <summary>
    /// True while a running game uses the profile (its folder must not be deleted under it). The launch flow sets
    /// this when it knows which profile each game started with; until then the page falls back to "a game of the
    /// profile's version is running while the profile is the selected one".
    /// </summary>
    public static Func<string, bool>? IsProfileInUse { get; set; }

    private readonly MainWindow _window;
    private readonly StatusStrip _status = new();
    private readonly UniformGrid _cards = new() { Columns = 3, Margin = new Thickness(-Space.S1) };
    private string? _pendingDelete;   // profile id awaiting the inline "Delete?" confirm
    private bool _busy;               // a duplicate's copy or a delete is running (off the UI thread)

    // Render runs only (Shots/ModsProfilesShots.cs): the player name and mod counts the made-up cards show.
    private string? _tourPlayer;
    private Func<ModProfile, int>? _tourCount;
    private bool Tour => _tourCount != null;

    internal Pressable? FirstUseButton { get; private set; }
    internal Pressable? NewCard { get; private set; }

    public ProfilesPage(MainWindow window)
    {
        _window = window;

        var intro = Ui.Body("Your setups. Each one keeps its own mods for one version, and can remember a server to join.");
        intro.MaxWidth = 520;
        intro.HorizontalAlignment = HorizontalAlignment.Left;
        intro.Margin = new Thickness(0, Space.S2, 0, 0);
        var import = Ui.Button("Import", Look.Secondary, Icons.Import, Sizes.ControlMd).Margin(0, 0, Space.S2, 0)
            .Named("Import a modpack or another launcher's profiles");
        import.Click += (_, _) => ImportView.Open(_window);
        var create = Ui.Button("New profile", Look.Primary, Icons.Plus, Sizes.ControlMd);
        create.Click += (_, _) => OpenForm(null);
        var buttons = Ui.Row(0, import, create);
        buttons.VerticalAlignment = VerticalAlignment.Top;
        buttons.Margin = new Thickness(Space.S6, Space.S2, 0, 0);
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 0, 0, Space.S6) };
        header.Children.Add(Ui.Column(0, Ui.Heading("Profiles"), intro).At(0));
        header.Children.Add(buttons.At(1));

        Children.Add(ModsParts.Column(this, Ui.Column(0, header, _status, _cards), out _));
        LauncherState.SettingsChanged += () => { if (IsVisible && !_busy) Rebuild(); };
    }

    public void Shown()
    {
        _tourPlayer = null;
        _tourCount = null;
        _pendingDelete = null;
        _status.Hide();
        // Finish removing any profile folder a past delete could only park.
        var versions = LauncherState.Settings.ModProfiles.Where(ProfileRules.IsUsable).Select(p => p.Version).Distinct().ToList();
        _ = Task.Run(() =>
        {
            foreach (var version in versions) ProfileFolders.SweepParked(OriginPaths.ProfilesDir(version));
        });
        Rebuild();
    }

    private static bool InUse(ModProfile profile)
    {
        if (IsProfileInUse is { } hook) return hook(profile.Id);
        if (LauncherState.Settings.SelectedProfileId != profile.Id) return false;
        if (MainWindow.Current?.Home.Launches.Book is not { } book) return false;
        return book.PlayingKeys().Any(k => book.VersionOf(k) == profile.Version);
    }

    private int CountMods(ModProfile profile) =>
        _tourCount?.Invoke(profile) ?? ProfileFolders.CountMods(OriginPaths.ProfileModsDir(profile.Version, profile.Id));

    // ---- cards ------------------------------------------------------------------------------------------------

    private void Rebuild()
    {
        _cards.Children.Clear();
        FirstUseButton = null;
        var settings = LauncherState.Settings;
        var profiles = settings.ModProfiles.Where(ProfileRules.IsUsable).ToList();
        var selectedId = profiles.Any(p => p.Id == settings.SelectedProfileId) ? settings.SelectedProfileId : null;
        var account = Tour ? null : LauncherState.Account;
        var player = Tour ? _tourPlayer : account?.Gamertag;
        var playerId = account?.Id ?? "";

        _cards.Children.Add(Ringed(BuildNoneCard(selectedId == null), selectedId == null));
        foreach (var profile in profiles)
        {
            var selected = profile.Id == selectedId;
            var asking = profile.Id == _pendingDelete;
            _cards.Children.Add(Ringed(asking ? BuildConfirmCard(profile) : BuildProfileCard(profile, selected, player, playerId),
                selected, asking));
        }
        _cards.Children.Add(Ringed(BuildNewCard(), false));
    }

    // The selected card's ring: 2 px accent at a 2 px gap. Every cell has the ring space (clear when not selected),
    // so selecting never shifts a card. A card asking to be deleted wears the ring in danger instead and is only as
    // tall as its question.
    private static Border Ringed(Control card, bool selected, bool danger = false) => new()
    {
        BorderThickness = new Thickness(2),
        Padding = new Thickness(2),
        CornerRadius = new CornerRadius(Radius.Lg + 2),
        BorderBrush = danger ? Palette.Danger : selected ? Palette.Accent : Brushes.Transparent,
        VerticalAlignment = danger ? VerticalAlignment.Top : VerticalAlignment.Stretch,
        Child = card,
    };

    private static Border CardShell() => new()
    {
        Background = Palette.Panel,
        CornerRadius = new CornerRadius(Radius.Lg),
        Padding = new Thickness(Space.S4),
    };

    // The emblem: the profile's icon (or first letter) in its tint's ink, on that tint.
    private static Border Emblem(IBrush tint, Control face) => new()
    {
        Width = Sizes.TileLg, Height = Sizes.TileLg, CornerRadius = new CornerRadius(Radius.Sm),
        Background = tint, VerticalAlignment = VerticalAlignment.Top, Child = face,
    };

    private static Control EmblemFace(string? icon, string name, IBrush ink) =>
        ProfileLook.EmblemGlyph(icon, ink) ?? Letter(ProfileLook.Initial(name), ink);

    private static TextBlock Letter(string initial, IBrush ink)
    {
        var t = Ui.Text(initial, TypeScale.Title, FontWeight.ExtraBold, ink);
        t.HorizontalAlignment = HorizontalAlignment.Center;
        t.VerticalAlignment = VerticalAlignment.Center;
        return t;
    }

    private static Border ProfileEmblem(ModProfile p) => Emblem(ProfileLook.Tint(p), EmblemFace(p.Icon, p.Name, ProfileLook.Ink(p)));

    // No profile = plain Origin, so its emblem is the logo file, never a bare letter.
    private static Border MarkEmblem()
    {
        var logo = Ui.Logo(Sizes.LogoBar);
        logo.HorizontalAlignment = HorizontalAlignment.Center;
        logo.VerticalAlignment = VerticalAlignment.Center;
        return Emblem(Palette.CardHover, logo);
    }

    // A name wraps to a second line before it is cut (cards narrow to ~220 px at the smallest window).
    private static TextBlock CardTitle(string text)
    {
        var t = Ui.Title(text);
        t.TextWrapping = TextWrapping.Wrap;
        t.MaxHeight = 44;
        t.TextTrimming = TextTrimming.CharacterEllipsis;
        return t;
    }

    private static TextBlock Small(string text, IBrush? brush = null, bool bold = false)
    {
        var t = Ui.Text(text, TypeScale.Caption, bold ? FontWeight.SemiBold : FontWeight.Normal,
            bold ? Palette.Text : brush ?? Palette.TextDim);
        t.VerticalAlignment = VerticalAlignment.Center;
        return t;
    }

    // The card's top: emblem, name and the second line. Edit / duplicate / delete stay out of sight until the card
    // is hovered or tabbed into, so a resting card is just the setup; the bar lies over the end of the name on the
    // hovered card's colour instead of keeping a column of its own.
    private static Grid CardHead(Border emblem, string name, Control second, Border card, params Pressable[] actions)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        grid.Children.Add(emblem.At(0));
        var text = Ui.Column(0, CardTitle(name), second);
        text.VerticalAlignment = VerticalAlignment.Top;
        text.Margin = new Thickness(Space.S3, 0, Space.S2, 0);
        grid.Children.Add(text.At(1));
        if (actions.Length == 0) return grid;

        var bar = Ui.Row(0, actions);
        bar.Background = Palette.Card;
        bar.VerticalAlignment = VerticalAlignment.Top;
        bar.HorizontalAlignment = HorizontalAlignment.Right;
        bar.Margin = new Thickness(0, -6, -Space.S2, 0);
        Grid.SetColumnSpan(bar, 2);
        grid.Children.Add(bar.At(1));
        void Update()
        {
            var shown = card.IsPointerOver || card.IsKeyboardFocusWithin || actions.Any(a => a.ForceFocus);
            bar.Opacity = shown ? 1 : 0;
            bar.IsHitTestVisible = shown;
        }
        card.PointerEntered += (_, _) => { card.Background = Palette.Card; Update(); };
        card.PointerExited += (_, _) => { card.Background = Palette.Panel; Update(); };
        card.PropertyChanged += (_, e) => { if (e.Property == IsKeyboardFocusWithinProperty) Update(); };
        Update();
        return grid;
    }

    private static StackPanel PlaysAs(string? player, string playerId)
    {
        var line = Ui.Row(0, Small(player != null ? "Plays as" : "Plays as the account you pick"));
        line.Margin = new Thickness(0, Space.S1, 0, 0);
        if (player == null) return line;
        var head = Ui.Pixel(App.Core.Skins.PlayerHeads.Loaded(playerId) ?? App.Core.Skins.PlayerHeads.Default(playerId), 14);
        head.Margin = new Thickness(6, 0, 5, 0);
        head.VerticalAlignment = VerticalAlignment.Center;
        line.Children.Add(head);
        line.Children.Add(Small(player, bold: true));
        return line;
    }

    // The mods line: a square per mod (its initial when the name is known), a "+N" square past five, then the count.
    private StackPanel ModsLine(ModProfile profile)
    {
        var count = CountMods(profile);
        var names = Tour ? [] : App.Core.Mods.ModManager.Enumerate(profile.Version, profile.Id)
            .Where(m => !m.IsManaged).Select(m => m.DisplayName).ToList();
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, Space.S3, 0, 0) };
        var shown = Math.Min(count, 5);
        for (var i = 0; i < shown; i++)
        {
            Control face = i < names.Count
                ? Centered(Ui.Text(ProfileLook.Initial(names[i]), TypeScale.Caption, FontWeight.Bold, Palette.TextDim))
                : Ui.Icon(Icons.Jar, Sizes.IconSm, Palette.TextMuted);
            line.Children.Add(ModSquare(face));
        }
        if (count > shown)
            line.Children.Add(ModSquare(Centered(Ui.Text($"+{count - shown}", TypeScale.Caption, FontWeight.Bold, Palette.Text))));
        var label = Small(count == 0 ? "No mods yet" : count == 1 ? "1 mod" : $"{count} mods");
        label.Margin = new Thickness(count == 0 ? 0 : Space.S2, 0, 0, 0);
        line.Children.Add(label);
        return line;
    }

    private static TextBlock Centered(TextBlock t)
    {
        t.HorizontalAlignment = HorizontalAlignment.Center;
        t.VerticalAlignment = VerticalAlignment.Center;
        return t;
    }

    private static Border ModSquare(Control face)
    {
        face.HorizontalAlignment = HorizontalAlignment.Center;
        face.VerticalAlignment = VerticalAlignment.Center;
        return new Border
        {
            Width = Sizes.TileSm, Height = Sizes.TileSm, Margin = new Thickness(0, 0, Space.S1, 0),
            Background = Palette.Bg, CornerRadius = new CornerRadius(Radius.Sm), Child = face,
        };
    }

    // Where it goes: "Joins <server>" or "Singleplayer".
    private static StackPanel WhereLine(ModProfile profile)
    {
        var server = ProfileRules.ServerLabel(profile);
        var line = Ui.Row(0, Ui.Icon(server != null ? Icons.Server : Icons.Globe, 13, Palette.TextMuted));
        line.Margin = new Thickness(0, Space.S2, 0, 0);
        line.Children.Add(Small(server != null ? "Joins" : "Singleplayer").Margin(Space.S2, 0, 0, 0));
        if (server != null) line.Children.Add(Small(server, bold: true).Margin(6, 0, 0, 0));
        return line;
    }

    // The foot: "Use profile" (or the Selected mark), and the version chip at the right.
    private Grid CardFoot(bool selected, string name, Action use, string? version, bool running)
    {
        var foot = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, Space.S3, 0, 0) };
        if (selected)
        {
            var mark = Ui.Row(Space.S2, Ui.Dot(Palette.Accent), Small(running ? "Selected / Running" : "Selected", bold: true));
            mark.Height = Sizes.ControlSm;
            foot.Children.Add(mark.At(0));
        }
        else
        {
            var button = Ui.Button("Use profile", Look.Secondary, height: Sizes.ControlSm).Named(name);
            button.HorizontalAlignment = HorizontalAlignment.Left;
            button.Click += (_, _) => use();
            FirstUseButton ??= button;
            foot.Children.Add(button.At(0));
        }
        if (version != null) foot.Children.Add(ModsParts.Chip(version, out _).At(1));
        return foot;
    }

    // The card body: a top-to-bottom stack with the foot pinned to the bottom (cards in one row share a height).
    private static DockPanel CardBody(Control foot, params Control[] top)
    {
        var dock = new DockPanel { LastChildFill = false };
        DockPanel.SetDock(foot, Dock.Bottom);
        dock.Children.Add(foot);
        foreach (var part in top)
        {
            DockPanel.SetDock(part, Dock.Top);
            dock.Children.Add(part);
        }
        return dock;
    }

    private Control BuildNoneCard(bool selected)
    {
        var card = CardShell();
        var sub = Small("Plain Origin").Margin(0, 6, 0, 0);
        var head = CardHead(MarkEmblem(), "No profile", sub, card);
        card.PointerEntered += (_, _) => card.Background = Palette.Card;
        card.PointerExited += (_, _) => card.Background = Palette.Panel;
        var note = Small("Your normal mods for each version, exactly as before.", Palette.TextMuted).Margin(0, Space.S4, 0, 0);
        note.TextWrapping = TextWrapping.Wrap;
        card.Child = CardBody(CardFoot(selected, "Use no profile", () => Select(null), null, false), head, note);
        return card.Named("No profile");
    }

    private Control BuildProfileCard(ModProfile profile, bool selected, string? player, string playerId)
    {
        var card = CardShell();
        var edit = Action(Icons.Edit, $"Edit {profile.Name}", () => OpenForm(profile));
        var copy = Action(Icons.Copy, $"Duplicate {profile.Name}", () => Duplicate(profile));
        var delete = Action(Icons.Trash, $"Delete {profile.Name}", () => AskDelete(profile));
        var head = CardHead(ProfileEmblem(profile), profile.Name, PlaysAs(player, playerId), card, edit, copy, delete);
        var foot = CardFoot(selected, $"Use {profile.Name}", () => Select(profile), profile.Version, !Tour && selected && InUse(profile));
        card.Child = CardBody(foot, head, ModsLine(profile), WhereLine(profile));
        return card.Named($"{profile.Name}, Minecraft {profile.Version}");
    }

    // An icon with no box and no hover label: the accessible name carries the meaning.
    private static Pressable Action(string glyph, string name, Action run)
    {
        var b = Ui.IconButton(glyph, name, Sizes.ControlSm);
        ToolTip.SetTip(b, null);
        b.Click += (_, _) => run();
        return b;
    }

    // Inline destructive confirm (same pattern as the Mods page): the card turns into the question.
    private Control BuildConfirmCard(ModProfile profile)
    {
        var card = CardShell();
        var mods = CountMods(profile);
        var sub = Small(mods switch
        {
            0 => "It has no mods.",
            1 => "Its 1 mod is deleted with it.",
            _ => $"Its {mods} mods are deleted with it.",
        }).Margin(0, 6, 0, 0);
        var head = CardHead(ProfileEmblem(profile), $"Delete {profile.Name}?", sub, card);
        var confirm = Ui.Button("Delete", Look.Danger, height: Sizes.ControlSm).Margin(0, 0, Space.S2, 0);
        confirm.Click += (_, _) => ConfirmDelete(profile);
        var cancel = Ui.Button("Cancel", Look.Secondary, height: Sizes.ControlSm);
        cancel.Click += (_, _) => { _pendingDelete = null; Rebuild(); };
        var buttons = Ui.Row(0, confirm, cancel);
        buttons.Margin = new Thickness(0, Space.S4, 0, 0);
        card.Child = CardBody(buttons, head);
        return card;
    }

    // The dashed "New profile" card at the end of the grid.
    private Control BuildNewCard()
    {
        var dash = Dash.Outline(Palette.LineStrong);
        var plus = Ui.Icon(Icons.Plus, Sizes.Icon, Palette.TextDim);
        plus.HorizontalAlignment = HorizontalAlignment.Center;
        plus.Margin = new Thickness(0, 0, 0, Space.S2);
        var title = Ui.Text("New profile", TypeScale.Title, FontWeight.Bold, Palette.TextDim);
        title.HorizontalAlignment = HorizontalAlignment.Center;
        var sub = Small("Its own mods, one version", Palette.TextMuted).Margin(0, Space.S1, 0, 0);
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        var content = Ui.Column(0, plus, title, sub);
        content.HorizontalAlignment = HorizontalAlignment.Center;
        content.VerticalAlignment = VerticalAlignment.Center;
        content.Margin = new Thickness(Space.S4);
        var body = new Panel();
        body.Children.Add(dash);
        body.Children.Add(content);
        // Plain paints nothing itself (the constructor's first Apply may leave a fill behind): clear at rest.
        var card = new Pressable(Look.Plain, body, "New profile") { CornerRadius = new CornerRadius(Radius.Lg), Background = Brushes.Transparent };
        card.StateChanged += p =>
        {
            var hot = p.IsHovered || p.ForceHover;
            card.Background = p.IsPressed ? Palette.Card : hot ? Palette.Panel : Brushes.Transparent;
            dash.Stroke = hot ? Palette.TextMuted : Palette.LineStrong;
            var ink = hot ? Palette.Text : Palette.TextDim;
            plus.Brush = ink;
            title.Foreground = ink;
        };
        card.Click += (_, _) => OpenForm(null);
        NewCard = card;
        return card;
    }

    // ---- select, duplicate, delete -----------------------------------------------------------------------------

    // One job: record which profile Play uses. Picking a profile also moves Home to its version.
    private void Select(ModProfile? profile)
    {
        if (Tour) return;
        _status.Hide();
        LauncherState.Write(s =>
        {
            s.SelectedProfileId = profile?.Id;
            if (profile != null) s.SelectedVersion = profile.Version;
        });
        Rebuild();
    }

    private async void Duplicate(ModProfile source)
    {
        if (Tour || _busy) return;
        _busy = true;
        try
        {
            ModProfile? copy = null;
            string? error = null;
            LauncherState.Write(s => error = ProfileBook.Duplicate(s.ModProfiles, source.Id, out copy));
            if (error != null || copy == null)
            {
                _status.Show(error, error: true);
                return;
            }
            var from = OriginPaths.ProfileModsDir(source.Version, source.Id);
            var to = OriginPaths.ProfileModsDir(copy.Version, copy.Id);
            var copyError = await Task.Run(() => ProfileFolders.CopyMods(from, to));
            _status.Show(copyError == null
                ? $"Copied to \"{copy.Name}\"."
                : $"Created \"{copy.Name}\", but not every mod could be copied. {copyError}", error: copyError != null);
        }
        finally
        {
            _busy = false;
            Rebuild();
        }
    }

    private void AskDelete(ModProfile profile)
    {
        if (InUse(profile))
        {
            _status.Show($"\"{profile.Name}\" is being used by a game that's running. Close Minecraft first.", error: true);
            return;
        }
        _status.Hide();
        _pendingDelete = profile.Id;
        Rebuild();
    }

    private async void ConfirmDelete(ModProfile profile)
    {
        _pendingDelete = null;
        if (Tour || _busy) return;
        if (InUse(profile)) // the game may have started since the confirm was asked
        {
            _status.Show($"\"{profile.Name}\" is being used by a game that's running. Close Minecraft first.", error: true);
            Rebuild();
            return;
        }
        _busy = true;
        try
        {
            var error = await Task.Run(() => ProfileFolders.Delete(OriginPaths.ProfileDir(profile.Version, profile.Id)));
            if (error != null)
            {
                _status.Show(error, error: true);
                return;
            }
            LauncherState.Write(s =>
            {
                ProfileBook.Remove(s.ModProfiles, profile.Id);
                if (s.SelectedProfileId == profile.Id) s.SelectedProfileId = null;
            });
            _status.Show($"Deleted \"{profile.Name}\".", error: false);
        }
        finally
        {
            _busy = false;
            Rebuild();
        }
    }

    // ---- the New / Edit form -----------------------------------------------------------------------------------

    private ProfileForm? _form;

    private void OpenForm(ModProfile? editing)
    {
        _pendingDelete = null;
        _status.Hide();
        Rebuild();
        var form = new ProfileForm(editing, FreshTint(), LauncherState.SelectedVersion, Save, () => _window.CloseDialog());
        _form = form;
        _window.OpenDialog(form, dismissable: false, onClosed: () => { if (_form == form) _form = null; }, width: Sizes.Dialog);
        form.FocusName();
    }

    // A new profile starts on the first colour no other profile wears.
    private static string FreshTint()
    {
        var worn = LauncherState.Settings.ModProfiles.Where(ProfileRules.IsUsable).Select(ProfileLook.TintName).ToHashSet();
        return ProfileLook.TintNames.FirstOrDefault(t => !worn.Contains(t)) ?? ProfileLook.TintNames[0];
    }

    private void Save(ProfileForm form)
    {
        if (Tour) return;
        var (name, serverName, address, tint, icon) = (form.NameText, form.ServerNameText, form.AddressText, form.Tint, form.Icon);
        string? error = null;
        ModProfile? created = null;
        if (form.EditingId is { } editId)
        {
            LauncherState.Write(s =>
            {
                error = ProfileBook.Edit(s.ModProfiles, editId, name, serverName, address);
                if (error == null && s.ModProfiles.FirstOrDefault(x => x.Id == editId) is { } edited)
                {
                    edited.Tint = tint;
                    edited.Icon = icon;
                }
            });
        }
        else
        {
            var version = form.Version;
            LauncherState.Write(s =>
            {
                error = ProfileBook.Create(s.ModProfiles, name, version, serverName, address, out created);
                if (error == null && created != null)
                {
                    created.Tint = tint;
                    created.Icon = icon;
                    // A new profile is the one you are about to add mods to.
                    s.SelectedProfileId = created.Id;
                    s.SelectedVersion = created.Version;
                }
            });
        }

        if (error != null)
        {
            // The server rule is checked after the name rule, so an error in the server rule's own words belongs to
            // the address box; anything else to the name box.
            form.ShowError(error, ProfileRules.ValidateServer(serverName, address) == error);
            return;
        }
        if (created != null)
        {
            try { Directory.CreateDirectory(OriginPaths.ProfileModsDir(created.Version, created.Id)); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* made on first use */ }
            _status.Show($"Created \"{created.Name}\". Add its mods on the Mods page.", error: false);
        }
        else _status.Hide();
        _window.CloseDialog();
        Rebuild();
    }

    // ---- render runs only (Shots/ModsProfilesShots.cs) ---------------------------------------------------------

    internal void TourDemo(string player, Func<ModProfile, int> count)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _tourPlayer = player;
        _tourCount = count;
        Rebuild();
    }

    internal void TourOpenForm()
    {
        if (!OriginPaths.IsScratchRoot) return;
        OpenForm(null);
        _form?.TourFill("Creative builds", "Wine", "Diamond", "play.example.net");
    }

    internal void TourFormError(string name, string message)
    {
        if (!OriginPaths.IsScratchRoot) return;
        OpenForm(null);
        if (_form == null) return;
        _form.TourFill(name, null, null, null);
        _form.ShowError(message, ProfileRules.ValidateServer(_form.ServerNameText, _form.AddressText) == message);
    }

    internal void TourEditForm()
    {
        if (OriginPaths.IsScratchRoot && LauncherState.Settings.ModProfiles.FirstOrDefault(ProfileRules.IsUsable) is { } first)
            OpenForm(first);
    }

    internal void TourShowStatus(string message, bool error)
    {
        if (OriginPaths.IsScratchRoot) _status.Show(message, error);
    }

    internal void TourAskDelete()
    {
        if (!OriginPaths.IsScratchRoot || LauncherState.Settings.ModProfiles.FirstOrDefault(ProfileRules.IsUsable) is not { } first) return;
        _pendingDelete = first.Id;
        Rebuild();
    }
}

/// <summary>
/// The New / Edit profile form, shown in the window's dialog: emblem preview, name, emblem and colour pickers, the
/// version (fixed when editing), and the optional server. The header and the footer stay put; only the fields scroll
/// when the window is too short, so Create / Save is always on screen. Esc cancels, Enter in a box saves.
/// </summary>
internal sealed class ProfileForm : Grid
{
    private readonly Action<ProfileForm> _save;
    private readonly Action _cancel;
    private readonly TextBox _name = Box(32, "Name");
    private readonly TextBox _serverName = Box(32, "Server name");
    private readonly TextBox _address = Box(255, "Server to join");
    private readonly TextBlock _nameError = Ui.Caption("", Palette.DangerText);
    private readonly TextBlock _formError = Ui.Caption("", Palette.DangerText);
    private readonly Border _emblem = new()
    {
        Width = Sizes.TileLg, Height = Sizes.TileLg, CornerRadius = new CornerRadius(Radius.Sm),
    };
    private readonly WrapPanel _icons = new() { Margin = new Thickness(0, 0, 0, Space.S3) };
    private readonly StackPanel _tints = new() { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, Space.S4) };
    private readonly Dropdown _version = new(Sizes.ControlLg) { HorizontalAlignment = HorizontalAlignment.Stretch };

    public string? EditingId { get; }
    public string Tint { get; private set; }
    public string? Icon { get; private set; }
    public string NameText => _name.Text ?? "";
    public string ServerNameText => _serverName.Text ?? "";
    public string AddressText => _address.Text ?? "";
    public string? Version => _version.Value;

    public ProfileForm(ModProfile? editing, string freshTint, string homeVersion, Action<ProfileForm> save, Action cancel)
    {
        _save = save;
        _cancel = cancel;
        EditingId = editing?.Id;
        Tint = editing != null ? ProfileLook.TintName(editing) : freshTint;
        Icon = ProfileLook.HasIcon(editing?.Icon) ? editing!.Icon : null;
        RowDefinitions = new RowDefinitions("Auto,*,Auto");

        // Header: the live emblem, the title, a close X.
        var close = Ui.IconButton(Icons.Close, "Cancel", Sizes.ControlLg);
        ToolTip.SetTip(close, null);
        close.Click += (_, _) => _cancel();
        var head = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), Margin = new Thickness(0, 0, 0, 20) };
        head.Children.Add(_emblem.At(0));
        var title = Ui.Heading(editing == null ? "New profile" : "Edit profile");
        title.VerticalAlignment = VerticalAlignment.Center;
        title.Margin = new Thickness(Space.S3, 0, 0, 0);
        head.Children.Add(title.At(1));
        close.VerticalAlignment = VerticalAlignment.Center;
        head.Children.Add(close.At(2));
        Children.Add(head.At(0, 0));

        // Fields.
        _name.Text = editing?.Name ?? "";
        _serverName.Text = editing?.ServerName ?? "";
        _address.Text = editing?.ServerAddress ?? "";
        _nameError.IsVisible = false;
        _nameError.TextWrapping = TextWrapping.Wrap;
        _nameError.Margin = new Thickness(0, Space.S2, 0, 0);
        _formError.IsVisible = false;
        _formError.TextWrapping = TextWrapping.Wrap;
        _formError.Margin = new Thickness(0, Space.S2, 0, 0);
        _name.TextChanged += (_, _) =>
        {
            RefreshEmblems();
            // Typing answers the error. Avalonia raises TextChanged after the fact, so compare with the name the error
            // was shown for: the event from setting the text itself must not wipe the error straight away.
            if ((_nameError.IsVisible || _formError.IsVisible) && NameText != _errorName) ClearError();
        };
        BuildIcons();
        BuildTints();

        _version.Items = VersionCatalog.SupportedVersions.Select(v => new DropItem(v, v)).ToList();
        _version.SetValue(editing?.Version ?? (VersionCatalog.IsSupported(homeVersion) ? homeVersion : VersionCatalog.DefaultVersion));
        _version.IsEnabled = editing == null;
        _version.Opacity = editing == null ? 1 : 0.5;

        var pair = new Grid { ColumnDefinitions = new ColumnDefinitions("*,12,*"), Margin = new Thickness(0, 0, 0, Space.S4) };
        pair.Children.Add(Field("Version", _version).At(0));
        pair.Children.Add(Field("Server name (optional)", _serverName).At(2));

        var hint = Ui.Caption(editing == null
            ? "A profile belongs to one version. With a server, Home shows Join next to Play."
            : "This profile's version is fixed. With a server, Home shows Join next to Play.");
        hint.TextWrapping = TextWrapping.Wrap;
        hint.Margin = new Thickness(0, Space.S2, 0, 0);

        var fields = Ui.Column(0,
            Ui.Column(0, Label("Name"), _name, _nameError).Margin(0, 0, 0, Space.S4),
            Label("Emblem"), _icons,
            Label("Colour"), _tints,
            pair,
            Field("Server to join (optional)", _address),
            hint, _formError);
        Children.Add(new ScrollViewer
        {
            Content = fields,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        }.At(0, 1));

        // Footer.
        var cancelButton = Ui.Button("Cancel", Look.Ghost, height: Sizes.ControlLg).Margin(0, 0, Space.S2, 0);
        cancelButton.Click += (_, _) => _cancel();
        var saveButton = Ui.Button(editing == null ? "Create profile" : "Save", Look.Primary, height: Sizes.ControlLg);
        saveButton.Click += (_, _) => _save(this);
        var foot = Ui.Row(0, cancelButton, saveButton);
        foot.HorizontalAlignment = HorizontalAlignment.Right;
        foot.Margin = new Thickness(0, Space.S6, 0, 0);
        Children.Add(foot.At(0, 2));

        RefreshEmblems();
        PickIcon(Icon);
        PickTint(Tint);
        AddHandler(KeyDownEvent, OnKey, Avalonia.Interactivity.RoutingStrategies.Tunnel);
    }

    private void OnKey(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && !_version.IsOpen)
        {
            _cancel();
            e.Handled = true;
        }
        else if (e.Key == Key.Enter && IsInBox(e.Source))
        {
            _save(this);
            e.Handled = true;
        }
    }

    private static bool IsInBox(object? source)
    {
        for (var v = source as Visual; v != null; v = v.GetVisualParent())
            if (v is TextBox) return true;
        return false;
    }

    public void FocusName()
    {
        Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            _name.Focus();
            _name.SelectAll();
        });
    }

    private static TextBox Box(int max, string name)
    {
        var box = new TextBox { MaxLength = max, Height = Sizes.ControlLg, VerticalContentAlignment = VerticalAlignment.Center };
        Avalonia.Automation.AutomationProperties.SetName(box, name);
        return box;
    }

    private static TextBlock Label(string text)
    {
        var t = Ui.Text(text, TypeScale.Caption, FontWeight.SemiBold, Palette.TextDim);
        t.Margin = new Thickness(0, 0, 0, Space.S2);
        return t;
    }

    private static StackPanel Field(string label, Control input) => Ui.Column(0, Label(label), input);

    // An error is a caption in dangerText plus a danger edge on the box it belongs to.
    private string? _errorName;

    public void ShowError(string message, bool onServer)
    {
        ClearError();
        _errorName = NameText;
        var caption = onServer ? _formError : _nameError;
        caption.Text = message;
        caption.IsVisible = true;
        // The danger edge holds at rest, under the pointer and while focused (the theme's own edge brushes for
        // those states are overridden on this one box only).
        var box = onServer ? _address : _name;
        box.BorderBrush = Palette.Danger;
        foreach (var key in EdgeKeys) box.Resources[key] = Palette.Danger;
    }

    private static readonly string[] EdgeKeys = ["TextControlBorderBrushPointerOver", "TextControlBorderBrushFocused"];

    private void ClearError()
    {
        _nameError.IsVisible = false;
        _formError.IsVisible = false;
        foreach (var box in new[] { _name, _address })
        {
            box.ClearValue(TextBox.BorderBrushProperty);
            foreach (var key in EdgeKeys) box.Resources.Remove(key);
        }
    }

    // ---- emblem + colour pickers -------------------------------------------------------------------------------

    private readonly List<(string? Icon, Border Ring)> _iconTiles = [];
    private readonly List<(string Tint, Border Ring)> _tintTiles = [];

    private void BuildIcons()
    {
        foreach (var icon in new string?[] { null }.Concat(ProfileLook.EmblemIcons))
        {
            var ring = Tile(Palette.Field);
            var tile = new Pressable(Look.Plain, ring, icon == null ? "Emblem: first letter" : $"Emblem: {icon}")
            {
                Margin = new Thickness(0, 0, Space.S1, Space.S1), CornerRadius = new CornerRadius(Radius.Sm), Background = Brushes.Transparent,
                Padding = default, BorderThickness = default,
            };
            var picked = icon;
            tile.Click += (_, _) => PickIcon(picked);
            _iconTiles.Add((icon, ring));
            _icons.Children.Add(tile);
        }
    }

    private void BuildTints()
    {
        foreach (var name in ProfileLook.TintNames)
        {
            var ring = Tile(ProfileLook.TintBrush(name));
            var tile = new Pressable(Look.Plain, ring, $"Colour {name}")
            {
                Margin = new Thickness(0, 0, Space.S1, 0), CornerRadius = new CornerRadius(Radius.Sm), Background = Brushes.Transparent,
                Padding = default, BorderThickness = default,
            };
            var picked = name;
            tile.Click += (_, _) => PickTint(picked);
            _tintTiles.Add((name, ring));
            _tints.Children.Add(tile);
        }
    }

    private static Border Tile(IBrush fill) => new()
    {
        Width = Sizes.TileSm, Height = Sizes.TileSm, CornerRadius = new CornerRadius(Radius.Sm),
        Background = fill, BorderThickness = new Thickness(2), BorderBrush = Brushes.Transparent,
    };

    private void PickIcon(string? icon)
    {
        Icon = icon;
        foreach (var (i, ring) in _iconTiles) ring.BorderBrush = i == icon ? Palette.Accent : Brushes.Transparent;
        RefreshEmblems();
    }

    private void PickTint(string tint)
    {
        Tint = tint;
        foreach (var (t, ring) in _tintTiles) ring.BorderBrush = t == tint ? Palette.Accent : Brushes.Transparent;
        RefreshEmblems();
    }

    // The preview, the letter tile and the six swatches all follow the name, the icon and the colour.
    private void RefreshEmblems()
    {
        var initial = ProfileLook.Initial(NameText);
        var ink = ProfileLook.InkBrush(Tint);
        _emblem.Background = ProfileLook.TintBrush(Tint);
        _emblem.Child = ProfileLook.EmblemGlyph(Icon, ink) ?? Center(Ui.Text(initial, TypeScale.Title, FontWeight.ExtraBold, ink));
        foreach (var (icon, ring) in _iconTiles)
            ring.Child = icon == null
                ? Center(Ui.Text(initial, TypeScale.Body, FontWeight.Bold, Palette.Text))
                : ring.Child ?? ProfileLook.EmblemGlyph(icon, Palette.Text);
        foreach (var (tint, ring) in _tintTiles)
        {
            var tintInk = ProfileLook.InkBrush(tint);
            ring.Child = ProfileLook.EmblemGlyph(Icon, tintInk) ?? Center(Ui.Text(initial, TypeScale.Body, FontWeight.Bold, tintInk));
        }
    }

    private static TextBlock Center(TextBlock t)
    {
        t.HorizontalAlignment = HorizontalAlignment.Center;
        t.VerticalAlignment = VerticalAlignment.Center;
        return t;
    }

    // Render runs only: the form as a player halfway through making a profile.
    internal void TourFill(string name, string? tint, string? icon, string? address)
    {
        _name.Text = name;
        if (tint != null) PickTint(tint);
        if (icon != null) PickIcon(icon);
        if (address != null) _address.Text = address;
        RefreshEmblems();
    }
}
