using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

// Twin of the WPF UI/Pages/ProfilesPage.xaml.cs, through the same Core calls: create, rename, set a server,
// duplicate, delete and select mod profiles. A profile is a named set of the player's OWN mods for ONE Minecraft
// version; the list lives in LauncherSettings.ModProfiles and every change goes through SettingsStore.Update +
// ProfileBook. Selecting a profile also switches Home's version to the profile's.
public sealed partial class ProfilesPage : UserControl
{
    private readonly Func<string, bool> _isInUse;   // Home: is a running game using this profile id?
    private Func<ModProfile, int> _countMods = p => ProfileFolders.CountMods(OriginPaths.ProfileModsDir(p.Version, p.Id));
    private LauncherSettings? _demo;                 // render shots only: shown instead of the real settings
    private string? _homeVersion;
    private string? _editingId;
    private string? _pendingDelete;
    private bool _busy;
    private string _formTint = "Slate";
    private string? _formIcon;

    internal string? ShownVersion => _homeVersion;

    /// <summary>The header's Import button: MainWindow opens the import card.</summary>
    public event EventHandler? ImportRequested;

    // The XAML loader needs a parameterless constructor; the window always uses the one below.
    public ProfilesPage() : this(_ => false) { }

    public ProfilesPage(Func<string, bool> isInUse)
    {
        _isInUse = isInUse;
        InitializeComponent();
        VersionBox.ItemsSource = VersionCatalog.SupportedVersions;
        BuildTintRow();
        BuildIconRow();
        NewButton.Click += (_, _) => OpenForm(null);
        ImportButton.Click += (_, _) => ImportRequested?.Invoke(this, EventArgs.Empty);
        CloseButton.Click += (_, _) => CloseForm();
        CancelButton.Click += (_, _) => CloseForm();
        SaveButton.Click += (_, _) => Save();
        NameBox.TextChanged += (_, _) =>
        {
            UpdateFormEmblem();
            if (NameError.IsVisible) ClearFormError(); // typing answers the error
        };
        // Tunnel: a TextBox would otherwise keep Enter / Escape for itself.
        FormPanel.AddHandler(KeyDownEvent, FormPanel_KeyDown, RoutingStrategies.Tunnel);
    }

    /// <summary>MainWindow, each time the page is opened: reload from disk and rebuild.</summary>
    public void Show(string? homeVersion)
    {
        _demo = null;
        _homeVersion = homeVersion;
        _pendingDelete = null;
        CloseForm();
        HideStatus();

        // Finish removing any profile folder a past delete could only park.
        var versions = SettingsStore.Load().ModProfiles
            .Where(ProfileRules.IsUsable).Select(p => p.Version).Distinct().ToList();
        _ = Task.Run(() =>
        {
            foreach (var version in versions) ProfileFolders.SweepParked(OriginPaths.ProfilesDir(version));
        });

        Rebuild();
    }

    // ---- render shots only (UI/Shots/AreaBShots.cs) -------------------------------------------------------------

    internal void ShowDemo(LauncherSettings demo, Func<ModProfile, int> countMods)
    {
        if (!OriginPaths.IsScratchRoot) return;
        _demo = demo;
        _countMods = countMods;
        _homeVersion = demo.SelectedVersion;
        _pendingDelete = null;
        CloseForm();
        HideStatus();
        Rebuild();
    }

    internal void TourOpenForm()
    {
        OpenForm(null);
        NameBox.Text = "Creative builds";
        PickTint("Wine");
        PickIcon("Diamond");
        ServerAddressBox.Text = "play.example.net";
    }

    internal void TourFormError(string name, string message)
    {
        OpenForm(null);
        NameBox.Text = name;
        ShowFormError(message, ProfileRules.ValidateServer(ServerNameBox.Text, ServerAddressBox.Text) == message);
    }

    internal void TourEditForm()
    {
        var first = Current().ModProfiles.FirstOrDefault(ProfileRules.IsUsable);
        if (first != null) OpenForm(first);
    }

    internal void TourShowStatus(string message, bool error) => ShowStatus(message, error);

    internal void TourAskDelete()
    {
        var first = Current().ModProfiles.FirstOrDefault(ProfileRules.IsUsable);
        if (first == null) return;
        _pendingDelete = first.Id;
        Rebuild();
    }

    internal void TourClickImport() => ImportButton.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));

    internal Control? TourFirstProfileCard => RowsPanel.Children.Count > 1 ? RowsPanel.Children[1] : null;

    private LauncherSettings Current() => _demo ?? SettingsStore.Load();

    // ---- list ---------------------------------------------------------------------------------------------------

    private void Rebuild()
    {
        RowsPanel.Children.Clear();
        var settings = Current();
        var profiles = settings.ModProfiles.Where(ProfileRules.IsUsable).ToList();
        var selectedId = profiles.Any(p => p.Id == settings.SelectedProfileId) ? settings.SelectedProfileId : null;
        var account = _demo != null ? null : OriginLauncher.App.Core.Accounts.AccountStore.GetSelected(OriginLauncher.App.Core.Accounts.AccountStore.Load());
        var player = _demo != null ? "OriginPlayer" : account?.Gamertag;
        var playerUuid = account?.Id ?? "";

        RowsPanel.Children.Add(Ringed(BuildNoneCard(selectedId == null), selectedId == null));
        foreach (var profile in profiles)
        {
            var selected = profile.Id == selectedId;
            RowsPanel.Children.Add(Ringed(profile.Id == _pendingDelete
                ? BuildConfirmCard(profile)
                : BuildProfileCard(profile, selected, player, playerUuid), selected, profile.Id == _pendingDelete));
        }
        RowsPanel.Children.Add(Ringed(BuildNewCard(), false));
    }

    // The selected card's ring: 2 px accent at a 2 px gap. Every cell has the ring space (transparent when not
    // selected), so selecting never shifts a card. A card asking to be deleted wears the ring in danger instead.
    private static Border Ringed(Control card, bool selected, bool danger = false)
    {
        var ring = new Border
        {
            BorderThickness = new Thickness(2),
            Padding = new Thickness(2),
            CornerRadius = Res.Radius("Radius.Ring.Lg"),
            BorderBrush = Brushes.Transparent,
            Child = card,
        };
        if (danger)
        {
            ring.BorderBrush = Res.Brush("Brush.Danger");
            ring.VerticalAlignment = VerticalAlignment.Top;
        }
        else if (selected) ring.BorderBrush = Res.Brush("Brush.Accent");
        return ring;
    }

    private static Border CardShell() => new() { Theme = Res.Theme("Surface.Card") };

    private static Border Emblem(string initial, IBrush tint) => new()
    {
        Width = Res.D("Size.TileLg"),
        Height = Res.D("Size.TileLg"),
        CornerRadius = Res.Radius("Radius.Sm"),
        Background = tint,
        VerticalAlignment = VerticalAlignment.Top,
        Child = new TrackedText
        {
            Text = initial,
            FontFamily = Res.Font,
            FontSize = Res.D("FontSize.Title"),
            FontWeight = FontWeight.ExtraBold,
            Foreground = Res.Brush("Brush.Text"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        },
    };

    private static Border ProfileEmblem(ModProfile profile)
    {
        var ink = ProfileLook.Ink(profile);
        var emblem = Emblem(ProfileLook.Initial(profile.Name), ProfileLook.Tint(profile));
        if (emblem.Child is TrackedText letter) letter.Foreground = ink;
        if (ProfileLook.EmblemGlyph(profile.Icon, ink) is { } glyph) emblem.Child = glyph;
        return emblem;
    }

    // No profile = plain Origin, so its emblem is the Origin logo image, never a bare letter.
    private static Border MarkEmblem()
    {
        var emblem = Emblem("", Res.Brush("Brush.CardAlt"));
        emblem.Child = new BrandLogo
        {
            Width = Res.D("Size.Logo.Bar"),
            Height = Res.D("Size.Logo.Bar"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        return emblem;
    }

    // A name wraps to a second line before it is cut.
    private static TextBlock Title(string text) => new()
    {
        Text = text,
        Theme = Res.Theme("Text.Title"),
        TextWrapping = TextWrapping.Wrap,
        MaxHeight = 44,
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private static TextBlock Small(string text, string brushKey = "Brush.TextDim", bool bold = false) => new()
    {
        Text = text,
        Theme = Res.Theme("Text.CaptionDim"),
        FontWeight = bold ? FontWeight.SemiBold : FontWeight.Normal,
        Foreground = Res.Brush(bold ? "Brush.Text" : brushKey),
        VerticalAlignment = VerticalAlignment.Center,
    };

    private static IconPath Glyph(string key, double size = 14, string brushKey = "Brush.TextDim") => new()
    {
        Data = Res.Geo(key),
        Theme = Res.Theme("Icon.Base"),
        Stroke = Res.Brush(brushKey),
        Width = size,
        Height = size,
        VerticalAlignment = VerticalAlignment.Center,
    };

    // The card's top: emblem, name, and the second line. Edit / duplicate / delete stay out of sight until the card
    // is hovered or tabbed into (RevealOnHover).
    private static Grid CardHead(Border emblem, string name, Control second, params Button[] actions)
    {
        var grid = Res.Columns("Auto", "*", "Auto");
        grid.Children.Add(emblem);

        var text = new StackPanel { Margin = new Thickness(12, 0, 8, 0), VerticalAlignment = VerticalAlignment.Top };
        text.Children.Add(Title(name));
        text.Children.Add(second);
        grid.Children.Add(text.At(1));

        if (actions.Length > 0)
        {
            // The bar lies over the end of the name instead of keeping a column of its own; its backing is the
            // hovered card's colour, so it covers cleanly.
            var bar = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                VerticalAlignment = VerticalAlignment.Top,
                HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, -6, -8, 0),
                Opacity = 0,
                IsHitTestVisible = false,
                Background = Res.Brush("Brush.Card"),
            };
            foreach (var action in actions) bar.Children.Add(action);
            Grid.SetColumnSpan(bar, 2);
            grid.Children.Add(bar.At(1));
            grid.Tag = bar;
        }
        return grid;
    }

    private static void RevealOnHover(Border card, Grid head)
    {
        if (head.Tag is not Control bar) return;
        void Update()
        {
            var shown = card.IsPointerOver || card.IsKeyboardFocusWithin;
            bar.Opacity = shown ? 1 : 0;
            bar.IsHitTestVisible = shown;
        }
        card.PropertyChanged += (_, e) =>
        {
            if (e.Property == IsPointerOverProperty || e.Property == IsKeyboardFocusWithinProperty) Update();
        };
    }

    private static StackPanel PlaysAs(string? player, string playerUuid)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 0) };
        line.Children.Add(Small(player != null ? "Plays as" : "Plays as the account you pick"));
        if (player != null)
        {
            line.Children.Add(new PlayerHead { Uuid = playerUuid, Width = 14, Height = 14, Margin = new Thickness(6, 0, 5, 0), VerticalAlignment = VerticalAlignment.Center });
            line.Children.Add(Small(player, bold: true));
        }
        return line;
    }

    private static TextBlock SquareText(string text, string brushKey) => new()
    {
        Text = text,
        FontFamily = Res.Font,
        FontSize = Res.D("FontSize.Caption"),
        FontWeight = FontWeight.Bold,
        Foreground = Res.Brush(brushKey),
        HorizontalAlignment = HorizontalAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center,
    };

    // The mods line: a square per mod (its initial when the name is known), a "+N" square past five, then the count.
    private StackPanel ModsLine(ModProfile profile)
    {
        var count = _countMods(profile);
        var names = _demo != null
            ? new List<string>()
            : OriginLauncher.App.Core.Mods.ModManager.Enumerate(profile.Version, profile.Id).Where(m => !m.IsManaged).Select(m => m.DisplayName).ToList();
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
        var shown = Math.Min(count, 5);
        for (var i = 0; i < shown; i++)
        {
            var name = i < names.Count ? names[i] : null;
            Control face = name != null ? SquareText(ProfileLook.Initial(name), "Brush.TextDim") : Glyph("Icon.Jar", 14, "Brush.TextMuted");
            if (face is IconPath p) p.HorizontalAlignment = HorizontalAlignment.Center;
            line.Children.Add(ModSquare(face));
        }
        if (count > shown) line.Children.Add(ModSquare(SquareText($"+{count - shown}", "Brush.Text")));
        var label = Small(count == 0 ? "No mods yet" : count == 1 ? "1 mod" : $"{count} mods");
        label.Margin = new Thickness(count == 0 ? 0 : 8, 0, 0, 0);
        line.Children.Add(label);
        return line;
    }

    private static Border ModSquare(Control content) => new()
    {
        Width = Res.D("Size.TileSm"),
        Height = Res.D("Size.TileSm"),
        Margin = new Thickness(0, 0, 4, 0),
        Background = Res.Brush("Brush.Bg"),
        CornerRadius = Res.Radius("Radius.Sm"),
        Child = content,
    };

    // Where it goes: "Joins <server>" or "Singleplayer".
    private static StackPanel WhereLine(ModProfile profile)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 8, 0, 0) };
        var server = ProfileRules.ServerLabel(profile);
        line.Children.Add(Glyph(server != null ? "Icon.Server" : "Icon.Globe", 13, "Brush.TextMuted"));
        var text = Small(server != null ? "Joins" : "Singleplayer");
        text.Margin = new Thickness(8, 0, 0, 0);
        line.Children.Add(text);
        if (server != null)
        {
            var name = Small(server, bold: true);
            name.Margin = new Thickness(6, 0, 0, 0);
            line.Children.Add(name);
        }
        return line;
    }

    // The foot: "Use profile" (or the Selected mark), and the version tag at the right.
    private static Grid CardFoot(bool selected, string automationName, Action use, string? version, bool running)
    {
        var foot = Res.Columns("*", "Auto");
        foot.Margin = new Thickness(0, 12, 0, 0);
        foot.VerticalAlignment = VerticalAlignment.Bottom;
        foot.Children.Add(UseControl(selected, automationName, use, running));
        if (version != null)
        {
            var tag = new Border
            {
                Theme = Res.Theme("Surface.Chip"),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock { Text = version, Theme = Res.Theme("Surface.ChipText") },
            };
            foot.Children.Add(tag.At(1));
        }
        return foot;
    }

    // A top-to-bottom stack whose foot is pinned to the bottom (cards in one row share a height).
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
        var sub = Small("Plain Origin");
        sub.Margin = new Thickness(0, 6, 0, 0);
        var head = CardHead(MarkEmblem(), "No profile", sub);
        var note = Small("Your normal mods for each version, exactly as before.", "Brush.TextMuted");
        note.Margin = new Thickness(0, 16, 0, 0);
        note.TextWrapping = TextWrapping.Wrap;
        card.Child = CardBody(CardFoot(selected, "Use no profile", () => Select(null), null, false), head, note);
        AutomationProperties.SetName(card, "No profile");
        return card;
    }

    private Control BuildProfileCard(ModProfile profile, bool selected, string? player, string playerUuid)
    {
        var card = CardShell();
        var edit = ModsPage.IconButton("Icon.Edit", $"Edit {profile.Name}");
        edit.Click += (_, _) => OpenForm(profile);
        var copy = ModsPage.IconButton("Icon.Copy", $"Duplicate {profile.Name}");
        copy.Click += (_, _) => Duplicate(profile);
        var delete = ModsPage.IconButton("Icon.Trash", $"Delete {profile.Name}");
        delete.Click += (_, _) => AskDelete(profile);

        var head = CardHead(ProfileEmblem(profile), profile.Name, PlaysAs(player, playerUuid), edit, copy, delete);
        RevealOnHover(card, head);
        var foot = CardFoot(selected, $"Use {profile.Name}", () => Select(profile), profile.Version, _demo == null && _isInUse(profile.Id));
        card.Child = CardBody(foot, head, ModsLine(profile), WhereLine(profile));
        AutomationProperties.SetName(card, $"{profile.Name}, Minecraft {profile.Version}");
        return card;
    }

    // Inline destructive-action confirm (same pattern as the Mods page).
    private Control BuildConfirmCard(ModProfile profile)
    {
        var card = CardShell();
        var mods = _countMods(profile);
        var sub = Small(mods switch
        {
            0 => "It has no mods.",
            1 => "Its 1 mod is deleted with it.",
            _ => $"Its {mods} mods are deleted with it.",
        });
        sub.Margin = new Thickness(0, 6, 0, 0);
        var head = CardHead(ProfileEmblem(profile), $"Delete {profile.Name}?", sub);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 16, 0, 0) };
        var confirm = new Button { Content = "Delete", Theme = Res.Theme("Button.Danger.Inline"), Margin = new Thickness(0, 0, 8, 0) };
        confirm.Click += (_, _) => ConfirmDelete(profile);
        buttons.Children.Add(confirm);
        var cancel = new Button { Content = "Cancel", Theme = Res.Theme("Button.Secondary.Inline") };
        cancel.Click += (_, _) => { _pendingDelete = null; Rebuild(); };
        buttons.Children.Add(cancel);

        card.Child = CardBody(buttons, head);
        return card;
    }

    private Control BuildNewCard()
    {
        var button = new Button
        {
            Theme = (Avalonia.Styling.ControlTheme)Resources["Card.New"]!,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
        };
        var content = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
        var plus = new IconPath
        {
            Data = Res.Geo("Icon.Plus"),
            Theme = Res.Theme("Icon.Base"),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 8),
        };
        plus.Bind(IconPath.StrokeProperty, button.GetObservable(ForegroundProperty));
        content.Children.Add(plus);
        var title = new TextBlock
        {
            Text = "New profile",
            FontFamily = Res.Font,
            FontSize = Res.D("FontSize.Title"),
            FontWeight = FontWeight.Bold,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        title.Bind(TextBlock.ForegroundProperty, button.GetObservable(ForegroundProperty));
        content.Children.Add(title);
        var sub = Small("Its own mods, one version", "Brush.TextMuted");
        // At 150-200% scaling the card is narrower than the line: it wraps, centred, instead of ending in "..." (WP-12).
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        sub.TextWrapping = TextWrapping.Wrap;
        sub.TextAlignment = TextAlignment.Center;
        sub.Margin = new Thickness(0, 4, 0, 0);
        content.Children.Add(sub);
        button.Content = content;
        button.Click += (_, _) => OpenForm(null);
        AutomationProperties.SetName(button, "New profile");
        return button;
    }

    // "Use profile" for a card that isn't selected; the Selected mark for the one that is. A running game adds
    // "Running".
    private static Control UseControl(bool selected, string automationName, Action use, bool running)
    {
        if (selected)
        {
            var mark = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Height = Res.D("Size.ControlSm") };
            var dot = new Border
            {
                Width = Res.D("Size.Dot"),
                Height = Res.D("Size.Dot"),
                Margin = new Thickness(0, 0, 8, 0),
                VerticalAlignment = VerticalAlignment.Center,
                Background = Res.Brush("Brush.Accent"),
            };
            Capsule.SetOn(dot, true);
            mark.Children.Add(dot);
            mark.Children.Add(Small(running ? "Selected / Running" : "Selected", bold: true));
            return mark;
        }
        var button = new Button
        {
            Content = "Use profile",
            Theme = Res.Theme("Button.Secondary.Inline"),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
        };
        AutomationProperties.SetName(button, automationName);
        button.Click += (_, _) => use();
        return button;
    }

    // ---- select -------------------------------------------------------------------------------------------------

    // One job: record which profile Play uses. Picking a profile also moves Home to its version.
    private void Select(ModProfile? profile)
    {
        if (_demo != null) return;
        SettingsStore.TryUpdate(s =>
        {
            s.SelectedProfileId = profile?.Id;
            if (profile != null) s.SelectedVersion = profile.Version;
        });
        if (profile != null) _homeVersion = profile.Version;
        HideStatus();
        Rebuild();
    }

    // ---- create / edit form -------------------------------------------------------------------------------------

    private void OpenForm(ModProfile? editing)
    {
        _editingId = editing?.Id;
        _pendingDelete = null;
        FormTitle.Text = editing == null ? "New profile" : "Edit profile";
        SaveButton.Content = editing == null ? "Create profile" : "Save";
        PickTint(editing != null ? ProfileLook.TintName(editing) : FreshTint());
        PickIcon(ProfileLook.HasIcon(editing?.Icon) ? editing!.Icon : null);
        NameBox.Text = editing?.Name ?? "";
        UpdateFormEmblem();
        ServerNameBox.Text = editing?.ServerName ?? "";
        ServerAddressBox.Text = editing?.ServerAddress ?? "";

        VersionBox.SelectedItem = editing?.Version
            ?? (VersionCatalog.IsSupported(_homeVersion) ? _homeVersion : VersionCatalog.DefaultVersion);
        VersionBox.IsEnabled = editing == null;
        VersionHint.Text = editing == null
            ? "A profile belongs to one version. With a server, Home shows Join next to Play."
            : "This profile's version is fixed. With a server, Home shows Join next to Play.";

        ClearFormError();
        FormPanel.IsVisible = true;
        HideStatus();
        Rebuild();
        NameBox.Focus();
        NameBox.SelectAll();
    }

    private void CloseForm()
    {
        _editingId = null;
        FormPanel.IsVisible = false;
    }

    // Escape cancels; Enter in a text box saves (the version list keeps Enter for itself).
    private void FormPanel_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            CloseForm();
            e.Handled = true;
        }
        else if (e.Key == Key.Enter && e.Source is TextBox or Visual { Parent: TextBox })
        {
            Save();
            e.Handled = true;
        }
    }

    // An error is a caption in the danger text plus a danger edge on the box it belongs to.
    private void ShowFormError(string message, bool onServer)
    {
        ClearFormError();
        var caption = onServer ? FormError : NameError;
        caption.Text = message;
        caption.IsVisible = true;
        (onServer ? ServerAddressBox : NameBox).Classes.Set("invalid", true);
    }

    private void ClearFormError()
    {
        FormError.IsVisible = false;
        NameError.IsVisible = false;
        NameBox.Classes.Set("invalid", false);
        ServerAddressBox.Classes.Set("invalid", false);
    }

    private void UpdateFormEmblem()
    {
        var initial = ProfileLook.Initial(NameBox.Text);
        FormInitial.Text = initial;
        FormEmblem.Background = ProfileLook.TintBrush(_formTint);
        FormInitial.Foreground = ProfileLook.InkBrush(_formTint);
        var glyph = ProfileLook.EmblemGlyph(_formIcon, ProfileLook.InkBrush(_formTint));
        FormInitial.IsVisible = glyph == null;
        FormIcon.Child = glyph;
        // The letter tile shows the letter the card would get.
        if (IconRow.Children.Count > 0 && ((Button)IconRow.Children[0]).Content is Border { Child: TextBlock letter })
            letter.Text = initial;
        // The colour swatches preview the current emblem (icon or letter) in each colour.
        foreach (var child in TintRow.Children)
            if (child is Button { Content: Border face, Tag: string tint }) face.Child = SwatchFace(tint);
    }

    private static Border TileFace(IBrush background, Control? child) => new()
    {
        Width = Res.D("Size.TileSm"),
        Height = Res.D("Size.TileSm"),
        CornerRadius = Res.Radius("Radius.Sm"),
        Background = background,
        BorderThickness = new Thickness(2),
        BorderBrush = Brushes.Transparent,
        Child = child,
    };

    private static Button Tile(object? tag, Border face, Thickness margin) => new()
    {
        Theme = Res.Theme("Button.Chrome"),
        Width = Res.D("Size.TileSm"),
        Height = Res.D("Size.TileSm"),
        MinWidth = 0,
        MinHeight = 0,
        Padding = new Thickness(0),
        Margin = margin,
        Tag = tag,
        Content = face,
    };

    // The emblem row: the first letter, then one tile per icon; the picked one wears the accent ring.
    private void BuildIconRow()
    {
        foreach (var icon in new string?[] { null }.Concat(ProfileLook.EmblemIcons))
        {
            Control face = icon == null
                ? new TextBlock
                {
                    Text = "P",
                    Theme = Res.Theme("Text.Body"),
                    FontWeight = FontWeight.Bold,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                }
                : ProfileLook.EmblemGlyph(icon, Res.Brush("Brush.Text"))!;
            var tile = Tile(icon, TileFace(Res.Brush("Brush.Field"), face), new Thickness(0, 0, 4, 4));
            AutomationProperties.SetName(tile, icon == null ? "Emblem: first letter" : $"Emblem: {icon}");
            tile.Click += (_, _) => PickIcon(icon);
            IconRow.Children.Add(tile);
        }
    }

    private void PickIcon(string? icon)
    {
        _formIcon = icon;
        foreach (var child in IconRow.Children)
            if (child is Button { Content: Border ring } tile)
                ring.BorderBrush = (string?)tile.Tag == icon ? Res.Brush("Brush.Accent") : Brushes.Transparent;
        UpdateFormEmblem();
    }

    // A new profile starts on the first colour no other profile wears.
    private string FreshTint()
    {
        var worn = Current().ModProfiles.Where(ProfileRules.IsUsable).Select(ProfileLook.TintName).ToHashSet();
        return ProfileLook.TintNames.FirstOrDefault(t => !worn.Contains(t)) ?? ProfileLook.TintNames[0];
    }

    private void BuildTintRow()
    {
        foreach (var name in ProfileLook.TintNames)
        {
            // The hue lives in the glyph as well as the tile, so the six read as six colours and not as greys.
            var swatch = Tile(name, TileFace(ProfileLook.TintBrush(name), SwatchFace(name)), new Thickness(0, 0, 4, 0));
            AutomationProperties.SetName(swatch, $"Colour {name}");
            swatch.Click += (_, _) => PickTint(name);
            TintRow.Children.Add(swatch);
        }
    }

    // What a colour swatch draws: the emblem the profile currently has, in that colour's ink.
    private Control SwatchFace(string tint)
    {
        var ink = ProfileLook.InkBrush(tint);
        if (ProfileLook.EmblemGlyph(_formIcon, ink) is { } glyph) return glyph;
        return new TextBlock
        {
            Text = ProfileLook.Initial(NameBox?.Text),
            Theme = Res.Theme("Text.Body"),
            FontWeight = FontWeight.Bold,
            Foreground = ink,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
    }

    private void PickTint(string name)
    {
        _formTint = name;
        foreach (var child in TintRow.Children)
            if (child is Button { Content: Border ring } swatch)
                ring.BorderBrush = (string?)swatch.Tag == name ? Res.Brush("Brush.Accent") : Brushes.Transparent;
        UpdateFormEmblem();
    }

    private void Save()
    {
        if (_demo != null) return;
        var name = NameBox.Text ?? "";
        var serverName = ServerNameBox.Text ?? "";
        var address = ServerAddressBox.Text ?? "";
        string? error = null;
        ModProfile? created = null;
        var tint = _formTint;
        var icon = _formIcon;

        if (_editingId is { } editId)
        {
            SettingsStore.TryUpdate(s =>
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
            var version = VersionBox.SelectedItem as string;
            SettingsStore.TryUpdate(s =>
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
            ShowFormError(error, ProfileRules.ValidateServer(serverName, address) == error);
            return;
        }

        if (created != null)
        {
            try { Directory.CreateDirectory(OriginPaths.ProfileModsDir(created.Version, created.Id)); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* made on first use */ }
            _homeVersion = created.Version;
            ShowStatus($"Created \"{created.Name}\". Add its mods on the Mods page.", error: false);
        }
        else HideStatus();
        CloseForm();
        Rebuild();
    }

    // ---- duplicate / delete -------------------------------------------------------------------------------------

    private async void Duplicate(ModProfile source)
    {
        if (_demo != null || _busy) return;
        _busy = true;
        try
        {
            ModProfile? copy = null;
            string? error = null;
            SettingsStore.TryUpdate(s => error = ProfileBook.Duplicate(s.ModProfiles, source.Id, out copy));
            if (error != null || copy == null)
            {
                ShowStatus(error, error: true);
                return;
            }
            var sourceMods = OriginPaths.ProfileModsDir(source.Version, source.Id);
            var copyMods = OriginPaths.ProfileModsDir(copy.Version, copy.Id);
            var copyError = await Task.Run(() => ProfileFolders.CopyMods(sourceMods, copyMods));
            ShowStatus(copyError == null
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
        if (_isInUse(profile.Id))
        {
            ShowStatus($"\"{profile.Name}\" is being used by a game that's running. Close Minecraft first.", error: true);
            return;
        }
        HideStatus();
        _pendingDelete = profile.Id;
        Rebuild();
    }

    private async void ConfirmDelete(ModProfile profile)
    {
        _pendingDelete = null;
        if (_demo != null || _busy) return;
        // The game may have started since the confirm was asked.
        if (_isInUse(profile.Id))
        {
            ShowStatus($"\"{profile.Name}\" is being used by a game that's running. Close Minecraft first.", error: true);
            Rebuild();
            return;
        }
        _busy = true;
        try
        {
            var error = await Task.Run(() => ProfileFolders.Delete(OriginPaths.ProfileDir(profile.Version, profile.Id)));
            if (error != null)
            {
                ShowStatus(error, error: true);
                return;
            }
            SettingsStore.TryUpdate(s =>
            {
                ProfileBook.Remove(s.ModProfiles, profile.Id);
                if (s.SelectedProfileId == profile.Id) s.SelectedProfileId = null;
            });
            if (_editingId == profile.Id) CloseForm();
            ShowStatus($"Deleted \"{profile.Name}\".", error: false);
        }
        finally
        {
            _busy = false;
            Rebuild();
        }
    }

    // ---- status line --------------------------------------------------------------------------------------------

    private void ShowStatus(string? message, bool error)
    {
        StatusText.Text = message ?? "Something went wrong.";
        StatusText.Foreground = Res.Brush(error ? "Brush.DangerText" : "Brush.Text");
        StatusDot.Background = Res.Brush(error ? "Brush.Danger" : "Brush.Accent");
        StatusBox.Background = Res.Brush(error ? "Brush.DangerSoft" : "Brush.Card");
        StatusBox.IsVisible = true;
    }

    private void HideStatus() => StatusBox.IsVisible = false;
}
