using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;
using OriginLauncher.App.Core.Mods;

namespace OriginLauncher.App.UI.Pages;

// Per-version external-mod manager. Reads/writes the same isolated
// /instances/{version}/mods/ folder VersionManager provisions on launch, so a
// mod toggled here is exactly what the game does (or doesn't) load. The version
// is pushed in by MainWindow from HomePage's live selection (see ShowVersion),
// so switching versions on Home swaps this whole list to match.
public partial class ModsPage : UserControl
{
    private string? _version;
    private string? _pendingRemove;   // filename awaiting the inline "Remove?" confirm
    private bool _suppressToggle;      // guards programmatic IsChecked changes

    public ModsPage()
    {
        InitializeComponent();
    }

    /// <summary>Point the page at a version and rebuild. Null = nothing
    /// selectable (version load failed / not chosen yet).</summary>
    public void ShowVersion(string? version)
    {
        _version = version;
        _pendingRemove = null;
        VersionChipText.Text = version ?? "No version";
        DropZone.IsEnabled = version != null;
        DropZone.Opacity = version != null ? 1.0 : 0.5;
        HideStatus();
        Refresh();
    }

    private void Refresh()
    {
        UserModsPanel.Children.Clear();
        BuiltInPanel.Children.Clear();

        if (_version == null)
        {
            UserEmptyState.Text = "Choose a version first";
            UserEmptyState.Visibility = Visibility.Visible;
            BuiltInSection.Visibility = Visibility.Collapsed;
            return;
        }

        var mods = ModManager.Enumerate(_version);
        var user = mods.Where(m => !m.IsManaged).ToList();
        var managed = mods.Where(m => m.IsManaged).ToList();

        foreach (var mod in user)
            UserModsPanel.Children.Add(
                mod.FileName == _pendingRemove ? BuildConfirmRow(mod) : BuildUserRow(mod));

        UserEmptyState.Text = "No mods";
        UserEmptyState.Visibility = user.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        foreach (var mod in managed)
            BuiltInPanel.Children.Add(BuildManagedRow(mod));
        BuiltInSection.Visibility = managed.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---- row builders -----------------------------------------------------

    private Border RowShell() => new()
    {
        Background = (Brush)FindResource("Brush.Panel"),
        BorderBrush = (Brush)FindResource("Brush.SoftStroke"),
        BorderThickness = new Thickness(1),
        CornerRadius = (CornerRadius)FindResource("Radius.Row"),
        Padding = new Thickness(14, 10, 10, 10),
        Margin = new Thickness(0, 0, 0, 8)
    };

    private FrameworkElement BuildUserRow(ModEntry mod)
    {
        var border = RowShell();
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var name = new TextBlock
        {
            Text = mod.DisplayName,
            Style = (Style)FindResource(mod.Enabled ? "Text.Body" : "Text.BodyDim"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0)
        };
        Grid.SetColumn(name, 0);
        grid.Children.Add(name);

        var toggle = new ToggleButton
        {
            Style = (Style)FindResource("Toggle.Switch"),
            IsChecked = mod.Enabled,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 4, 0),
            ToolTip = mod.Enabled ? "On — loads in-game" : "Off — skipped at launch"
        };
        toggle.Checked += (s, _) => OnToggle(mod.FileName, true, (ToggleButton)s);
        toggle.Unchecked += (s, _) => OnToggle(mod.FileName, false, (ToggleButton)s);
        Grid.SetColumn(toggle, 1);
        grid.Children.Add(toggle);

        var remove = IconButton("Icon.Trash", "Remove this mod");
        remove.Click += (_, _) => { _pendingRemove = mod.FileName; Refresh(); };
        Grid.SetColumn(remove, 2);
        grid.Children.Add(remove);

        border.Child = grid;
        return border;
    }

    // Inline destructive-action confirm (nothing lost by accident): the row
    // flips to "Remove <name>?" with explicit Remove / Cancel, rather than a
    // native dialog that would break the chromeless look.
    private FrameworkElement BuildConfirmRow(ModEntry mod)
    {
        var border = RowShell();
        border.BorderBrush = (Brush)FindResource("Brush.Danger");

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var prompt = new TextBlock
        {
            Text = $"Remove {mod.DisplayName}?",
            Style = (Style)FindResource("Text.Body"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0)
        };
        Grid.SetColumn(prompt, 0);
        grid.Children.Add(prompt);

        var confirm = new Button
        {
            Content = "Remove",
            Style = (Style)FindResource("Button.Base"),
            Background = (Brush)FindResource("Brush.Danger"),
            Foreground = (Brush)FindResource("Brush.TextOnAccent"),
            Padding = new Thickness(12, 6, 12, 6),
            Margin = new Thickness(0, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        confirm.Click += (_, _) =>
        {
            var result = ModManager.Remove(_version!, mod.FileName);
            _pendingRemove = null;
            if (!result.Ok) ShowStatus(result.Error);
            else HideStatus();
            Refresh();
        };
        Grid.SetColumn(confirm, 1);
        grid.Children.Add(confirm);

        var cancel = new Button
        {
            Content = "Cancel",
            Style = (Style)FindResource("Button.Secondary"),
            Padding = new Thickness(12, 6, 12, 6),
            VerticalAlignment = VerticalAlignment.Center
        };
        cancel.Click += (_, _) => { _pendingRemove = null; Refresh(); };
        Grid.SetColumn(cancel, 2);
        grid.Children.Add(cancel);

        border.Child = grid;
        return border;
    }

    private FrameworkElement BuildManagedRow(ModEntry mod)
    {
        var border = RowShell();
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var name = new TextBlock
        {
            Text = mod.DisplayName,
            Style = (Style)FindResource("Text.BodyDim"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0)
        };
        Grid.SetColumn(name, 0);
        grid.Children.Add(name);

        var tag = new TextBlock
        {
            Text = "MANAGED",
            Style = (Style)FindResource("Text.Eyebrow"),
            VerticalAlignment = VerticalAlignment.Center
        };
        Grid.SetColumn(tag, 1);
        grid.Children.Add(tag);

        border.Child = grid;
        return border;
    }

    private Button IconButton(string iconKey, string tooltip)
    {
        var path = new Path
        {
            Data = (Geometry)FindResource(iconKey),
            Style = (Style)FindResource("Icon.Base"),
            Stroke = (Brush)FindResource("Brush.TextDim"),
            Width = 18,
            Height = 18
        };
        return new Button
        {
            Content = path,
            Style = (Style)FindResource("Button.Chrome"),
            Width = 34,
            Height = 34,
            Padding = new Thickness(0),
            ToolTip = tooltip,
            VerticalAlignment = VerticalAlignment.Center
        };
    }

    // ---- actions ----------------------------------------------------------

    private void OnToggle(string fileName, bool enable, ToggleButton toggle)
    {
        if (_suppressToggle || _version == null) return;

        var result = ModManager.SetEnabled(_version, fileName, enable);
        if (result.Ok)
        {
            toggle.ToolTip = enable ? "On — loads in-game" : "Off — skipped at launch";
            HideStatus();
        }
        else
        {
            // Revert the switch to disk truth without re-firing this handler.
            _suppressToggle = true;
            toggle.IsChecked = !enable;
            _suppressToggle = false;
            ShowStatus(result.Error);
        }
    }

    private void DropZone_Click(object sender, MouseButtonEventArgs e)
    {
        if (_version == null) return;
        var result = ModManager.OpenFolder(_version);
        if (!result.Ok) ShowStatus(result.Error);
    }

    private void DropZone_DragEnter(object sender, DragEventArgs e)
    {
        if (_version != null && HasJar(e))
        {
            e.Effects = DragDropEffects.Copy;
            DropZone.BorderBrush = (Brush)FindResource("Brush.Accent");
            DropZone.Background = (Brush)FindResource("Brush.SoftStroke");
        }
        else
        {
            e.Effects = DragDropEffects.None;
        }
        e.Handled = true;
    }

    private void DropZone_DragLeave(object sender, DragEventArgs e)
    {
        DropZone.BorderBrush = (Brush)FindResource("Brush.Stroke");
        DropZone.Background = (Brush)FindResource("Brush.Panel");
    }

    private void DropZone_Drop(object sender, DragEventArgs e)
    {
        DropZone_DragLeave(sender, e);
        if (_version == null || !HasJar(e)) return;

        var files = (string[])e.Data.GetData(DataFormats.FileDrop);
        var result = ModManager.Import(_version, files);
        if (!result.Ok) ShowStatus(result.Error);
        else HideStatus();
        Refresh();
    }

    private static bool HasJar(DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return false;
        var files = e.Data.GetData(DataFormats.FileDrop) as string[];
        return files != null && files.Any(f =>
            f.EndsWith(ModManager.JarSuffix, StringComparison.OrdinalIgnoreCase));
    }

    private void ShowStatus(string? message)
    {
        StatusText.Text = message ?? "Something went wrong.";
        StatusText.Visibility = Visibility.Visible;
    }

    private void HideStatus()
    {
        StatusText.Visibility = Visibility.Collapsed;
    }
}
