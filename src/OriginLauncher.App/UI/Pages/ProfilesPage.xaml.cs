using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Shapes;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Pages;

// Mod profiles: create, rename, set a server, duplicate, delete and select.
//
// A profile is a named set of the player's OWN mods for ONE Minecraft version
// (its folder: instances/<version>/profiles/<id>/mods, paths via OriginPaths).
// The list lives in LauncherSettings.ModProfiles and every change to it goes
// through SettingsStore.Update + ProfileBook (so validation is the tested pure
// rules). Selecting a profile also switches Home's version to the profile's.
// Mods are added to the selected profile on the Mods page.
public partial class ProfilesPage : UserControl
{
    private readonly Func<string, bool> _isInUse;   // Home: is a running game using this profile id?
    private Func<ModProfile, int> _countMods = p => ProfileFolders.CountMods(OriginPaths.ProfileModsDir(p.Version, p.Id));
    private LauncherSettings? _demo;                 // CI screen render only: shown instead of the real settings
    private string? _homeVersion;                    // the version Home shows; the create form starts on it
    private string? _editingId;                      // the profile the form is editing; null = creating a new one
    private string? _pendingDelete;                  // profile id awaiting the inline "Delete?" confirm
    private bool _busy;                              // a duplicate / delete is copying or removing files
    private string _formTint = "Slate";              // the colour picked in the form
    private string? _formIcon;                        // the emblem icon picked (null = first letter)

    public ProfilesPage(Func<string, bool> isInUse)
    {
        _isInUse = isInUse;
        InitializeComponent();
        VersionBox.ItemsSource = VersionCatalog.SupportedVersions;
        BuildTintRow();
        BuildIconRow();
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

    /// <summary>
    /// CI screen render only (UI/RenderShots.cs): shows made-up profiles without
    /// reading or writing the real settings or folders.
    /// </summary>
    internal void ShowDemo(LauncherSettings demo, Func<ModProfile, int> countMods)
    {
        _demo = demo;
        _countMods = countMods;
        _homeVersion = demo.SelectedVersion;
        _pendingDelete = null;
        CloseForm();
        HideStatus();
        Rebuild();
    }

    // CI render: the form as a player halfway through making a profile.
    internal void TourOpenForm()
    {
        OpenForm(null);
        NameBox.Text = "Creative builds";
        PickTint("Wine");
        PickIcon("Diamond");
        ServerAddressBox.Text = "play.example.net";
    }

    /// <summary>CI screen render: presses the header's Import button the way a click does.</summary>
    internal void TourClickImport() => ImportButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));

    private LauncherSettings Current() => _demo ?? SettingsStore.Load();

    // ---- list ------------------------------------------------------------

    // Cards, not rows: a profile should feel like a setup you own (emblem, name,
    // who it plays as, its mods, where it goes), with the version only a small tag.
    private void Rebuild()
    {
        RowsPanel.Children.Clear();
        var settings = Current();
        var profiles = settings.ModProfiles.Where(ProfileRules.IsUsable).ToList();
        var selectedId = profiles.Any(p => p.Id == settings.SelectedProfileId) ? settings.SelectedProfileId : null;
        var player = _demo != null
            ? "OriginPlayer"
            : Core.Accounts.AccountStore.GetSelected(Core.Accounts.AccountStore.Load())?.Gamertag;

        RowsPanel.Children.Add(BuildNoneCard(selectedId == null));
        foreach (var profile in profiles)
        {
            RowsPanel.Children.Add(profile.Id == _pendingDelete
                ? BuildConfirmCard(profile)
                : BuildProfileCard(profile, profile.Id == selectedId, player));
        }
        RowsPanel.Children.Add(BuildNewCard());
    }

    // The selected card's ring is its own version's colour (No profile: Home's family).
    private Border CardShell(bool selected, string? version = null)
    {
        var card = new Border { Style = (Style)FindResource("Card.Profile") };
        if (selected && version != null) card.BorderBrush = FamilyTheme.AccentBrush(version);
        else if (selected) card.SetResourceReference(Border.BorderBrushProperty, "Brush.Acc");
        return card;
    }

    // The emblem: the profile's initial on its own muted tint (ProfileLook).
    private Border Emblem(string initial, Brush tint)
    {
        var emblem = new Border
        {
            Width = 44,
            Height = 44,
            CornerRadius = (CornerRadius)FindResource("Radius.Sharp"),
            Background = tint,
            VerticalAlignment = VerticalAlignment.Top,
        };
        emblem.Child = new Controls.TrackedText
        {
            Text = initial,
            FontFamily = (FontFamily)FindResource("Font.Display"),
            FontSize = 20,
            FontWeight = FontWeights.ExtraBold,
            Foreground = (Brush)FindResource("Brush.Text"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        return emblem;
    }

    // A profile's emblem: its icon when it has one, else its first letter, on its tint.
    private Border ProfileEmblem(ModProfile profile)
    {
        var emblem = Emblem(ProfileLook.Initial(profile.Name), ProfileLook.Tint(profile));
        if (ProfileLook.EmblemGlyph(profile.Icon, 22, (Brush)FindResource("Brush.Text")) is { } glyph)
            emblem.Child = glyph;
        return emblem;
    }

    // No profile = plain Origin, so its emblem is the Origin mark, never a bare letter.
    private Border MarkEmblem()
    {
        var emblem = Emblem("", (Brush)FindResource("Brush.CardAlt"));
        emblem.Child = new Controls.OriginMark
        {
            Width = 28,
            Height = 28,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        return emblem;
    }

    // A name wraps to a second line before it is cut (cards narrow to ~220 px at the smallest window).
    private TextBlock Title(string text) => new()
    {
        Text = text,
        Style = (Style)FindResource("Text.CardTitle"),
        TextWrapping = TextWrapping.Wrap,
        MaxHeight = 44,
        TextTrimming = TextTrimming.CharacterEllipsis,
    };

    private TextBlock Small(string text, string brushKey = "Brush.TextDim", bool bold = false) => new()
    {
        Text = text,
        Style = (Style)FindResource("Text.Meta"),
        FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
        Foreground = (Brush)FindResource(bold ? "Brush.Text" : brushKey),
        VerticalAlignment = VerticalAlignment.Center,
    };

    private Path Glyph(string key, double size = 14, string brushKey = "Brush.TextDim") => new()
    {
        Data = (Geometry)FindResource(key),
        Style = (Style)FindResource("Icon.Base"),
        Stroke = (Brush)FindResource(brushKey),
        Width = size,
        Height = size,
        VerticalAlignment = VerticalAlignment.Center,
    };

    // The card's top: emblem, name, and the second line (a face + "Plays as X").
    // Edit / duplicate / delete stay out of sight until the card is hovered or
    // tabbed into, so a resting card is just the setup (RevealOnHover).
    private Grid CardHead(Border emblem, string name, UIElement second, params Button[] actions)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(emblem);

        var text = new StackPanel { Margin = new Thickness(14, 1, 8, 0), VerticalAlignment = VerticalAlignment.Top };
        text.Children.Add(Title(name));
        text.Children.Add(second);
        AddAt(grid, 1, text);

        if (actions.Length > 0)
        {
            // The bar lies over the end of the name instead of keeping a column of its own,
            // so a resting card gives the name its full width (three cards across at 980 px
            // leave ~270 px). Its backing is the hovered card's colour, so it covers cleanly.
            var bar = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                VerticalAlignment = VerticalAlignment.Top,
                HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, -6, -8, 0),
                Opacity = 0,
                IsHitTestVisible = false,
            };
            bar.SetResourceReference(Panel.BackgroundProperty, "Brush.Card");
            foreach (var action in actions) bar.Children.Add(action);
            Grid.SetColumnSpan(bar, 2);
            AddAt(grid, 1, bar);
            grid.Tag = bar;
        }
        return grid;
    }

    // Shows the head's action bar while the card is hovered or holds keyboard focus.
    private static void RevealOnHover(Border card, Grid head)
    {
        if (head.Tag is not UIElement bar) return;
        // Hidden, the bar must not catch clicks meant for the name either.
        void Update()
        {
            var shown = card.IsMouseOver || card.IsKeyboardFocusWithin;
            bar.Opacity = shown ? 1 : 0;
            bar.IsHitTestVisible = shown;
        }
        card.MouseEnter += (_, _) => Update();
        card.MouseLeave += (_, _) => Update();
        card.IsKeyboardFocusWithinChanged += (_, _) => Update();
    }

    private StackPanel PlaysAs(string? player)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 4, 0, 0) };
        line.Children.Add(Small(player != null ? "Plays as" : "Plays as the account you pick"));
        if (player != null)
        {
            line.Children.Add(new Controls.PixelFace { Seed = player, Width = 14, Height = 14, Margin = new Thickness(6, 0, 5, 0), VerticalAlignment = VerticalAlignment.Center });
            line.Children.Add(Small(player, bold: true));
        }
        return line;
    }

    // The mods line: a square per mod (its initial when the name is known), a "+N"
    // square past five, then the count.
    private StackPanel ModsLine(ModProfile profile)
    {
        var count = _countMods(profile);
        var names = _demo != null
            ? new List<string>()
            : Core.Mods.ModManager.Enumerate(profile.Version, profile.Id).Where(m => !m.IsManaged).Select(m => m.DisplayName).ToList();
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 12, 0, 0) };
        var shown = Math.Min(count, 5);
        for (var i = 0; i < shown; i++)
        {
            var name = i < names.Count ? names[i] : null;
            UIElement face = name != null
                ? new TextBlock { Text = ProfileLook.Initial(name), FontFamily = (FontFamily)FindResource("Font.Display"), FontSize = 12, FontWeight = FontWeights.Bold, Foreground = (Brush)FindResource("Brush.TextDim"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }
                : Glyph("Icon.Jar", 14, "Brush.TextMuted");
            var square = ModSquare(face);
            if (name != null) square.ToolTip = name;
            line.Children.Add(square);
        }
        if (count > shown)
            line.Children.Add(ModSquare(new TextBlock { Text = $"+{count - shown}", FontFamily = (FontFamily)FindResource("Font.Display"), FontSize = 11.5, FontWeight = FontWeights.Bold, Foreground = (Brush)FindResource("Brush.Text"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }));
        var label = Small(count == 0 ? "No mods yet" : count == 1 ? "1 mod" : $"{count} mods");
        label.Margin = new Thickness(count == 0 ? 0 : 8, 0, 0, 0);
        line.Children.Add(label);
        return line;
    }

    private Border ModSquare(UIElement content) => new()
    {
        Width = 26,
        Height = 26,
        Margin = new Thickness(0, 0, 4, 0),
        Background = (Brush)FindResource("Brush.Bg"),
        CornerRadius = (CornerRadius)FindResource("Radius.Sharp"),
        Child = content,
    };

    // Where it goes: "Joins <server>" or "Singleplayer".
    private StackPanel WhereLine(ModProfile profile)
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
    private Grid CardFoot(bool selected, string automationName, Action use, string? version, bool running)
    {
        var foot = new Grid { Margin = new Thickness(0, 12, 0, 0), VerticalAlignment = VerticalAlignment.Bottom };
        foot.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        foot.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        foot.Children.Add(UseControl(selected, automationName, use, running, version));
        if (version != null)
        {
            // The version as a small chip, the card's only number.
            var tag = new Border
            {
                Background = (Brush)FindResource("Brush.Bg"),
                CornerRadius = (CornerRadius)FindResource("Radius.Chip"),
                Padding = new Thickness(8, 3, 8, 3),
                VerticalAlignment = VerticalAlignment.Center,
                Child = new TextBlock
                {
                    Text = version,
                    FontFamily = (FontFamily)FindResource("Font.Display"),
                    FontSize = 11.5,
                    FontWeight = FontWeights.Bold,
                    Foreground = (Brush)FindResource("Brush.TextDim"),
                },
            };
            AddAt(foot, 1, tag);
        }
        return foot;
    }

    // The card body is a top-to-bottom stack whose foot is pinned to the bottom
    // (cards in one row share a height).
    private static DockPanel CardBody(UIElement foot, params UIElement[] top)
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

    private FrameworkElement BuildNoneCard(bool selected)
    {
        var card = CardShell(selected);
        var sub = Small("Plain Origin");
        sub.Margin = new Thickness(0, 6, 0, 0);
        var head = CardHead(MarkEmblem(), "No profile", sub);
        var note = Small("Your normal mods for each version, exactly as before.", "Brush.TextMuted");
        note.Margin = new Thickness(0, 16, 0, 0);
        note.TextWrapping = TextWrapping.Wrap;
        card.Child = CardBody(CardFoot(selected, "Use no profile", () => Select(null), null, false), head, note);
        System.Windows.Automation.AutomationProperties.SetName(card, "No profile");
        return card;
    }

    private FrameworkElement BuildProfileCard(ModProfile profile, bool selected, string? player)
    {
        var card = CardShell(selected, profile.Version);

        var edit = IconButton("Icon.Edit", "Edit", $"Edit {profile.Name}");
        edit.Click += (_, _) => OpenForm(profile);
        var copy = IconButton("Icon.Copy", "Duplicate", $"Duplicate {profile.Name}");
        copy.Click += (_, _) => Duplicate(profile);
        var delete = IconButton("Icon.Trash", "Delete", $"Delete {profile.Name}");
        delete.Click += (_, _) => AskDelete(profile);

        var head = CardHead(ProfileEmblem(profile), profile.Name, PlaysAs(player), edit, copy, delete);
        RevealOnHover(card, head);
        var foot = CardFoot(selected, $"Use {profile.Name}", () => Select(profile), profile.Version, _demo == null && _isInUse(profile.Id));
        card.Child = CardBody(foot, head, ModsLine(profile), WhereLine(profile));
        System.Windows.Automation.AutomationProperties.SetName(card, $"{profile.Name}, Minecraft {profile.Version}");
        return card;
    }

    // Inline destructive-action confirm (same pattern as the Mods page): the card
    // turns into the question, with Delete / Cancel.
    private FrameworkElement BuildConfirmCard(ModProfile profile)
    {
        var card = CardShell(false);
        card.BorderBrush = (Brush)FindResource("Brush.Danger");

        var sub = Small("Its mods are deleted with it");
        sub.Margin = new Thickness(0, 6, 0, 0);
        var head = CardHead(ProfileEmblem(profile), $"Delete {profile.Name}?", sub);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 16, 0, 0) };
        var confirm = new Button
        {
            Content = "Delete",
            Style = (Style)FindResource("Button.Danger"),
            Padding = new Thickness(14, 0, 14, 0),
            Height = 32,
            Margin = new Thickness(0, 0, 8, 0),
        };
        confirm.Click += (_, _) => ConfirmDelete(profile);
        buttons.Children.Add(confirm);
        var cancel = new Button
        {
            Content = "Cancel",
            Style = (Style)FindResource("Button.Secondary"),
            Padding = new Thickness(14, 0, 14, 0),
            Height = 32,
        };
        cancel.Click += (_, _) => { _pendingDelete = null; Rebuild(); };
        buttons.Children.Add(cancel);

        card.Child = CardBody(buttons, head);
        return card;
    }

    private FrameworkElement BuildNewCard()
    {
        var content = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
        var plus = new Path
        {
            Data = (Geometry)FindResource("Icon.Plus"),
            Style = (Style)FindResource("Icon.Base"),
            Width = 18,
            Height = 18,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 8),
        };
        var button = new Button { Style = (Style)FindResource("Card.New") };
        plus.SetBinding(Shape.StrokeProperty, new System.Windows.Data.Binding(nameof(Foreground)) { Source = button });
        content.Children.Add(plus);
        content.Children.Add(new TextBlock { Text = "New profile", FontFamily = (FontFamily)FindResource("Font.Display"), FontSize = 14, FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center });
        var sub = Small("Its own mods, one version", "Brush.TextMuted");
        sub.HorizontalAlignment = HorizontalAlignment.Center;
        sub.Margin = new Thickness(0, 4, 0, 0);
        content.Children.Add(sub);
        button.Content = content;
        button.Click += (_, _) => OpenForm(null);
        System.Windows.Automation.AutomationProperties.SetName(button, "New profile");
        return button;
    }

    private static void AddAt(Grid grid, int column, UIElement element)
    {
        Grid.SetColumn(element, column);
        grid.Children.Add(element);
    }

    // "Use profile" for a card that isn't selected; the Selected mark (an accent
    // square, an indicator) for the one that is. A running game adds "Running".
    private FrameworkElement UseControl(bool selected, string automationName, Action use, bool running, string? version)
    {
        if (selected)
        {
            var mark = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Height = 32 };
            var dot = new Rectangle { Width = 8, Height = 8, Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center };
            if (version != null) dot.Fill = FamilyTheme.AccentBrush(version);
            else dot.SetResourceReference(Shape.FillProperty, "Brush.Acc");
            mark.Children.Add(dot);
            mark.Children.Add(Small(running ? "Selected / Running" : "Selected", bold: true));
            return mark;
        }
        var button = new Button
        {
            Content = "Use profile",
            Style = (Style)FindResource("Button.Secondary"),
            Padding = new Thickness(14, 0, 14, 0),
            Height = 32,
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center
        };
        System.Windows.Automation.AutomationProperties.SetName(button, automationName);
        button.Click += (_, _) => use();
        return button;
    }

    private Button IconButton(string iconKey, string tooltip, string automationName)
    {
        var button = new Button
        {
            Style = (Style)FindResource("Button.Chrome"),
            Width = 34,
            Height = 34,
            Padding = new Thickness(0),
            ToolTip = tooltip,
            VerticalAlignment = VerticalAlignment.Center
        };
        var path = new Path
        {
            Data = (Geometry)FindResource(iconKey),
            Style = (Style)FindResource("Icon.Base"),
            Width = 16,
            Height = 16
        };
        // The glyph follows the button's own foreground (dim at rest, bright on hover).
        path.SetBinding(Shape.StrokeProperty, new System.Windows.Data.Binding(nameof(Foreground)) { Source = button });
        button.Content = path;
        System.Windows.Automation.AutomationProperties.SetName(button, automationName);
        return button;
    }

    // ---- select ----------------------------------------------------------

    // One job: record which profile Play uses. Picking a profile also moves
    // Home to its version (a profile belongs to exactly one).
    private void Select(ModProfile? profile)
    {
        if (_demo != null) return;
        SettingsStore.Update(s =>
        {
            s.SelectedProfileId = profile?.Id;
            if (profile != null) s.SelectedVersion = profile.Version;
        });
        if (profile != null) _homeVersion = profile.Version;
        HideStatus();
        Rebuild();
    }

    // ---- create / edit form ----------------------------------------------

    private void NewButton_Click(object sender, RoutedEventArgs e) => OpenForm(null);

    /// <summary>The header's Import button: MainWindow opens the import card (Core/Import, UI/Controls/ImportPanel).</summary>
    public event EventHandler? ImportRequested;

    private void ImportButton_Click(object sender, RoutedEventArgs e) => ImportRequested?.Invoke(this, EventArgs.Empty);

    private void OpenForm(ModProfile? editing)
    {
        _editingId = editing?.Id;
        _pendingDelete = null;
        FormTitle.Text = editing == null ? "NEW PROFILE" : "EDIT PROFILE";
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

        FormError.Visibility = Visibility.Collapsed;
        FormPanel.Visibility = Visibility.Visible;
        HideStatus();
        Rebuild();
        NameBox.Focus();
        NameBox.SelectAll();
    }

    private void CloseForm()
    {
        _editingId = null;
        FormPanel.Visibility = Visibility.Collapsed;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e) => CloseForm();

    // Escape cancels; Enter in a text box saves (the version list keeps Enter for itself).
    private void FormPanel_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.Escape)
        {
            CloseForm();
            e.Handled = true;
        }
        else if (e.Key == System.Windows.Input.Key.Enter && e.OriginalSource is TextBox)
        {
            SaveButton_Click(SaveButton, new RoutedEventArgs());
            e.Handled = true;
        }
    }

    private void NameBox_TextChanged(object sender, TextChangedEventArgs e) => UpdateFormEmblem();

    private void UpdateFormEmblem()
    {
        var initial = ProfileLook.Initial(NameBox.Text);
        FormInitial.Text = initial;
        FormEmblem.Background = ProfileLook.TintBrush(_formTint);
        var glyph = ProfileLook.EmblemGlyph(_formIcon, 20, (Brush)FindResource("Brush.Text"));
        FormInitial.Visibility = glyph == null ? Visibility.Visible : Visibility.Collapsed;
        FormIcon.Child = glyph;
        // The letter tile shows the letter the card would get.
        if (IconRow.Children.Count > 0 && ((Button)IconRow.Children[0]).Content is Border { Child: TextBlock letter })
            letter.Text = initial;
    }

    // The emblem row: the first letter, then one tile per icon; the picked one wears
    // the accent ring (mockup 10).
    private void BuildIconRow()
    {
        foreach (var icon in new string?[] { null }.Concat(ProfileLook.EmblemIcons))
        {
            UIElement face = icon == null
                ? new TextBlock
                {
                    Text = "P",
                    Style = (Style)FindResource("Text.CardTitle"),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                }
                : ProfileLook.EmblemGlyph(icon, 18, (Brush)FindResource("Brush.Text"))!;
            var tile = new Button
            {
                Style = (Style)FindResource("Button.Chrome"),
                Width = 40,
                Height = 40,
                Padding = new Thickness(0),
                Margin = new Thickness(0, 0, 6, 6),
                Tag = icon,
                ToolTip = icon ?? "First letter",
                Content = new Border
                {
                    Width = 40,
                    Height = 40,
                    CornerRadius = (CornerRadius)FindResource("Radius.Swatch"),
                    Background = (Brush)FindResource("Brush.Inset"),
                    BorderThickness = new Thickness(2),
                    BorderBrush = Brushes.Transparent,
                    Child = face,
                },
            };
            System.Windows.Automation.AutomationProperties.SetName(tile, icon == null ? "Emblem: first letter" : $"Emblem: {icon}");
            tile.Click += (_, _) => PickIcon(icon);
            IconRow.Children.Add(tile);
        }
    }

    private void PickIcon(string? icon)
    {
        _formIcon = icon;
        foreach (Button tile in IconRow.Children)
        {
            var ring = (Border)tile.Content;
            if ((string?)tile.Tag == icon) ring.SetResourceReference(Border.BorderBrushProperty, "Brush.Acc");
            else ring.BorderBrush = Brushes.Transparent;
        }
        UpdateFormEmblem();
    }

    // A new profile starts on the first colour no other profile wears.
    private string FreshTint()
    {
        var worn = Current().ModProfiles.Where(ProfileRules.IsUsable).Select(ProfileLook.TintName).ToHashSet();
        return ProfileLook.TintNames.FirstOrDefault(t => !worn.Contains(t)) ?? ProfileLook.TintNames[0];
    }

    // The colour row: one square per tint; the picked one wears the accent ring.
    private void BuildTintRow()
    {
        foreach (var name in ProfileLook.TintNames)
        {
            var swatch = new Button
            {
                Style = (Style)FindResource("Button.Chrome"),
                Width = 36,
                Height = 36,
                Padding = new Thickness(0),
                Margin = new Thickness(0, 0, 8, 0),
                Tag = name,
                ToolTip = name,
                Content = new Border
                {
                    Width = 36,
                    Height = 36,
                    CornerRadius = (CornerRadius)FindResource("Radius.Swatch"),
                    Background = ProfileLook.TintBrush(name),
                    BorderThickness = new Thickness(2),
                    BorderBrush = Brushes.Transparent,
                },
            };
            System.Windows.Automation.AutomationProperties.SetName(swatch, $"Colour {name}");
            swatch.Click += (_, _) => PickTint(name);
            TintRow.Children.Add(swatch);
        }
    }

    private void PickTint(string name)
    {
        _formTint = name;
        foreach (Button swatch in TintRow.Children)
        {
            var ring = (Border)swatch.Content;
            if ((string)swatch.Tag == name) ring.SetResourceReference(Border.BorderBrushProperty, "Brush.Acc");
            else ring.BorderBrush = Brushes.Transparent;
        }
        FormEmblem.Background = ProfileLook.TintBrush(name);
    }

    private void SaveButton_Click(object sender, RoutedEventArgs e)
    {
        if (_demo != null) return;
        var name = NameBox.Text;
        var serverName = ServerNameBox.Text;
        var address = ServerAddressBox.Text;
        string? error = null;
        ModProfile? created = null;

        if (_editingId is { } editId)
        {
            var tint = _formTint;
            var icon = _formIcon;
            SettingsStore.Update(s =>
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
            var tint = _formTint;
            var icon = _formIcon;
            SettingsStore.Update(s =>
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
            FormError.Text = error;
            FormError.Visibility = Visibility.Visible;
            return;
        }

        if (created != null)
        {
            try { System.IO.Directory.CreateDirectory(OriginPaths.ProfileModsDir(created.Version, created.Id)); }
            catch (Exception ex) when (ex is System.IO.IOException or UnauthorizedAccessException) { /* made on first use */ }
            _homeVersion = created.Version;
            ShowStatus($"Created \"{created.Name}\". Add its mods on the Mods page.");
        }
        else
        {
            HideStatus();
        }
        CloseForm();
        Rebuild();
    }

    // ---- duplicate / delete ----------------------------------------------

    private async void Duplicate(ModProfile source)
    {
        if (_demo != null || _busy) return;
        _busy = true;
        try
        {
            ModProfile? copy = null;
            string? error = null;
            SettingsStore.Update(s => error = ProfileBook.Duplicate(s.ModProfiles, source.Id, out copy));
            if (error != null || copy == null)
            {
                ShowStatus(error);
                return;
            }

            var sourceMods = OriginPaths.ProfileModsDir(source.Version, source.Id);
            var copyMods = OriginPaths.ProfileModsDir(copy.Version, copy.Id);
            var copyError = await Task.Run(() => ProfileFolders.CopyMods(sourceMods, copyMods));
            ShowStatus(copyError == null
                ? $"Copied to \"{copy.Name}\"."
                : $"Created \"{copy.Name}\", but not every mod could be copied. {copyError}");
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
            ShowStatus($"\"{profile.Name}\" is being used by a game that's running. Close Minecraft first.");
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
            ShowStatus($"\"{profile.Name}\" is being used by a game that's running. Close Minecraft first.");
            Rebuild();
            return;
        }

        _busy = true;
        try
        {
            var error = await Task.Run(() => ProfileFolders.Delete(OriginPaths.ProfileDir(profile.Version, profile.Id)));
            if (error != null)
            {
                ShowStatus(error);
                return;
            }
            SettingsStore.Update(s =>
            {
                ProfileBook.Remove(s.ModProfiles, profile.Id);
                if (s.SelectedProfileId == profile.Id) s.SelectedProfileId = null;
            });
            if (_editingId == profile.Id) CloseForm();
            ShowStatus($"Deleted \"{profile.Name}\".");
        }
        finally
        {
            _busy = false;
            Rebuild();
        }
    }

    // ---- status line -----------------------------------------------------

    private void ShowStatus(string? message)
    {
        StatusText.Text = message ?? "Something went wrong.";
        StatusText.Visibility = Visibility.Visible;
    }

    private void HideStatus() => StatusText.Visibility = Visibility.Collapsed;
}
