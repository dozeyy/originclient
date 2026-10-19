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

    public ProfilesPage(Func<string, bool> isInUse)
    {
        _isInUse = isInUse;
        InitializeComponent();
        VersionBox.ItemsSource = VersionCatalog.SupportedVersions;
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

    internal void TourOpenForm() => OpenForm(null);

    /// <summary>CI screen render: presses the header's Import button the way a click does.</summary>
    internal void TourClickImport() => ImportButton.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));

    private LauncherSettings Current() => _demo ?? SettingsStore.Load();

    // ---- list ------------------------------------------------------------

    private void Rebuild()
    {
        RowsPanel.Children.Clear();
        var settings = Current();
        var profiles = settings.ModProfiles.Where(ProfileRules.IsUsable).ToList();
        var selectedId = profiles.Any(p => p.Id == settings.SelectedProfileId) ? settings.SelectedProfileId : null;

        RowsPanel.Children.Add(BuildNoneRow(selectedId == null));
        foreach (var profile in profiles)
        {
            RowsPanel.Children.Add(profile.Id == _pendingDelete
                ? BuildConfirmRow(profile)
                : BuildProfileRow(profile, profile.Id == selectedId));
        }
    }

    private Border RowShell(bool selected) => new()
    {
        Background = (Brush)FindResource("Brush.Panel"),
        BorderBrush = (Brush)FindResource(selected ? "Brush.Accent" : "Brush.SoftStroke"),
        BorderThickness = new Thickness(1),
        CornerRadius = (CornerRadius)FindResource("Radius.Row"),
        Padding = new Thickness(14, 10, 10, 10),
        Margin = new Thickness(0, 0, 0, 8)
    };

    private static Grid RowGrid(int autoColumns)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < autoColumns; i++)
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        return grid;
    }

    private StackPanel TitleBlock(string title, string detail, bool dim)
    {
        var panel = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 12, 0) };
        panel.Children.Add(new TextBlock
        {
            Text = title,
            Style = (Style)FindResource(dim ? "Text.BodyDim" : "Text.Body"),
            FontWeight = FontWeights.Medium,
            TextTrimming = TextTrimming.CharacterEllipsis
        });
        panel.Children.Add(new TextBlock
        {
            Text = detail,
            Style = (Style)FindResource("Text.Caption"),
            Margin = new Thickness(0, 2, 0, 0),
            TextTrimming = TextTrimming.CharacterEllipsis
        });
        return panel;
    }

    private FrameworkElement BuildNoneRow(bool selected)
    {
        var border = RowShell(selected);
        var grid = RowGrid(1);
        grid.Children.Add(TitleBlock("No profile", "Your normal mods, exactly as before", dim: !selected));
        AddAt(grid, 1, UseControl(selected, "Use no profile", () => Select(null)));
        border.Child = grid;
        return border;
    }

    private FrameworkElement BuildProfileRow(ModProfile profile, bool selected)
    {
        var border = RowShell(selected);
        var grid = RowGrid(4);

        var mods = _countMods(profile);
        var detail = $"{profile.Version} - {mods} {(mods == 1 ? "mod" : "mods")}";
        if (profile.HasServer) detail += $" - {profile.ServerName ?? profile.ServerAddress}";
        grid.Children.Add(TitleBlock(profile.Name, detail, dim: false));

        AddAt(grid, 1, UseControl(selected, $"Use {profile.Name}", () => Select(profile)));

        var edit = IconButton("Icon.Edit", "Edit", $"Edit {profile.Name}");
        edit.Click += (_, _) => OpenForm(profile);
        AddAt(grid, 2, edit);

        var copy = IconButton("Icon.Copy", "Duplicate", $"Duplicate {profile.Name}");
        copy.Click += (_, _) => Duplicate(profile);
        AddAt(grid, 3, copy);

        var delete = IconButton("Icon.Trash", "Delete", $"Delete {profile.Name}");
        delete.Click += (_, _) => AskDelete(profile);
        AddAt(grid, 4, delete);

        border.Child = grid;
        return border;
    }

    // Inline destructive-action confirm (same pattern as the Mods page).
    private FrameworkElement BuildConfirmRow(ModProfile profile)
    {
        var border = RowShell(false);
        border.BorderBrush = (Brush)FindResource("Brush.Danger");
        var grid = RowGrid(2);

        grid.Children.Add(TitleBlock($"Delete {profile.Name}?", "Its mods are deleted with it", dim: false));

        var confirm = new Button
        {
            Content = "Delete",
            Style = (Style)FindResource("Button.Base"),
            Background = (Brush)FindResource("Brush.Danger"),
            Foreground = (Brush)FindResource("Brush.TextOnAccent"),
            Padding = new Thickness(12, 6, 12, 6),
            Margin = new Thickness(0, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        confirm.Click += (_, _) => ConfirmDelete(profile);
        AddAt(grid, 1, confirm);

        var cancel = new Button
        {
            Content = "Cancel",
            Style = (Style)FindResource("Button.Secondary"),
            Padding = new Thickness(12, 6, 12, 6),
            VerticalAlignment = VerticalAlignment.Center
        };
        cancel.Click += (_, _) => { _pendingDelete = null; Rebuild(); };
        AddAt(grid, 2, cancel);

        border.Child = grid;
        return border;
    }

    private static void AddAt(Grid grid, int column, UIElement element)
    {
        Grid.SetColumn(element, column);
        grid.Children.Add(element);
    }

    // "Use" for a row that isn't selected; the SELECTED tag for the one that is.
    private FrameworkElement UseControl(bool selected, string automationName, Action use)
    {
        if (selected)
        {
            return new TextBlock
            {
                Text = "SELECTED",
                Style = (Style)FindResource("Text.Eyebrow"),
                Foreground = (Brush)FindResource("Brush.Accent"),
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 10, 0)
            };
        }
        var button = new Button
        {
            Content = "Use",
            Style = (Style)FindResource("Button.Secondary"),
            Padding = new Thickness(14, 6, 14, 6),
            Margin = new Thickness(0, 0, 6, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        System.Windows.Automation.AutomationProperties.SetName(button, automationName);
        button.Click += (_, _) => use();
        return button;
    }

    private Button IconButton(string iconKey, string tooltip, string automationName)
    {
        var path = new Path
        {
            Data = (Geometry)FindResource(iconKey),
            Style = (Style)FindResource("Icon.Base"),
            Stroke = (Brush)FindResource("Brush.TextDim"),
            Width = 18,
            Height = 18
        };
        var button = new Button
        {
            Content = path,
            Style = (Style)FindResource("Button.Chrome"),
            Width = 34,
            Height = 34,
            Padding = new Thickness(0),
            ToolTip = tooltip,
            VerticalAlignment = VerticalAlignment.Center
        };
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
        FormTitle.Text = editing == null ? "New profile" : "Edit profile";
        SaveButton.Content = editing == null ? "Create" : "Save";
        NameBox.Text = editing?.Name ?? "";
        ServerNameBox.Text = editing?.ServerName ?? "";
        ServerAddressBox.Text = editing?.ServerAddress ?? "";

        VersionBox.SelectedItem = editing?.Version
            ?? (VersionCatalog.IsSupported(_homeVersion) ? _homeVersion : VersionCatalog.DefaultVersion);
        VersionBox.IsEnabled = editing == null;
        VersionHint.Text = editing == null
            ? "A profile belongs to one version. It can't be changed later."
            : "This profile's version is fixed.";

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
            SettingsStore.Update(s => error = ProfileBook.Edit(s.ModProfiles, editId, name, serverName, address));
        }
        else
        {
            var version = VersionBox.SelectedItem as string;
            SettingsStore.Update(s =>
            {
                error = ProfileBook.Create(s.ModProfiles, name, version, serverName, address, out created);
                if (error == null && created != null)
                {
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
