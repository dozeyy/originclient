using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Controls;

// Full-screen version picker that overlays Home. Two states: a 3x3 grid of
// artwork cards (the landing), and — once a card is picked — a master/detail
// view with the families stacked down the left and the selected family's detail
// (hero image, description, specific-version picker, Launch + Mods) on the right.
// Cards are ListBox items so a click reliably selects; the control owns no
// launch logic beyond raising LaunchRequested.
public partial class VersionSelectOverlay : UserControl
{
    private bool _syncing;
    private long _sceneRevision;

    public VersionSelectOverlay()
    {
        InitializeComponent();
        GridList.ItemsSource = VersionCatalog.Groups;
        StackList.ItemsSource = VersionCatalog.Groups;
    }

    /// <summary>Raised when the player picks a specific version (keeps Home's
    /// current selection in sync as they browse).</summary>
    public event Action<string>? VersionSelected;

    /// <summary>Raised when the player clicks Launch on a version.</summary>
    public event Action<string>? LaunchRequested;

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(string? currentVersion)
    {
        // Reset to the grid landing each time it opens.
        ToGridState(animate: false);
        _syncing = true;
        GridList.SelectedItem = null;
        StackList.SelectedItem = null;
        _syncing = false;

        ++_sceneRevision;
        Visibility = Visibility.Visible;
        BeginAnimation(OpacityProperty, null);
        OverlayTranslate.BeginAnimation(TranslateTransform.YProperty, null);

        if (SystemParameters.ClientAreaAnimation)
        {
            var duration = (Duration)FindResource("Motion.Scene");
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, duration) { EasingFunction = ease },
                HandoffBehavior.SnapshotAndReplace);
            OverlayTranslate.BeginAnimation(TranslateTransform.YProperty,
                new DoubleAnimation(10, 0, duration) { EasingFunction = ease },
                HandoffBehavior.SnapshotAndReplace);
        }
        else
        {
            Opacity = 1;
            OverlayTranslate.Y = 0;
        }

        Dispatcher.BeginInvoke(() => GridList.Focus());
    }

    private void CloseOverlay()
    {
        var sceneRevision = ++_sceneRevision;
        if (!SystemParameters.ClientAreaAnimation)
        {
            Visibility = Visibility.Collapsed;
            return;
        }

        var fade = new DoubleAnimation(0, (Duration)FindResource("Motion.Exit"));
        fade.Completed += (_, _) =>
        {
            if (_sceneRevision == sceneRevision)
                Visibility = Visibility.Collapsed;
        };
        BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
    }

    // ---- state transitions ----

    private void ToGridState(bool animate)
    {
        DetailState.Visibility = Visibility.Collapsed;
        BackButton.Visibility = Visibility.Collapsed;
        GridState.Visibility = Visibility.Visible;
        if (animate)
        {
            GridState.BeginAnimation(OpacityProperty, null);
            GridState.Opacity = 0;
            GridState.BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, (Duration)FindResource("Motion.Base")),
                HandoffBehavior.SnapshotAndReplace);
        }
        else
        {
            GridState.BeginAnimation(OpacityProperty, null);
            GridState.Opacity = 1;
        }
    }

    private void ToDetailState(VersionGroup group)
    {
        GridState.Visibility = Visibility.Collapsed;
        DetailState.Visibility = Visibility.Visible;
        BackButton.Visibility = Visibility.Visible;

        _syncing = true;
        StackList.SelectedItem = group;
        _syncing = false;
        StackList.ScrollIntoView(group);
        ShowGroupDetail(group);

        // Fade + a small slide-in so the switch reads as the cards moving aside.
        DetailState.BeginAnimation(OpacityProperty, null);
        DetailState.Opacity = 0;
        var duration = SystemParameters.ClientAreaAnimation
            ? (Duration)FindResource("Motion.Scene")
            : new Duration(TimeSpan.Zero);
        DetailState.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, duration),
            HandoffBehavior.SnapshotAndReplace);
        var slide = new DoubleAnimation(-18, 0, duration)
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        };
        DetailSlide.BeginAnimation(TranslateTransform.XProperty, slide, HandoffBehavior.SnapshotAndReplace);
    }

    // Populate the right-hand detail for a family. Every version in the catalog
    // is playable (no "Coming Soon" entries), so Launch is always available.
    private void ShowGroupDetail(VersionGroup group)
    {
        DetailPanel.DataContext = group;
        VersionDropdown.ItemsSource = group.Versions;
        VersionDropdown.SelectedItem = group.Newest;

        // Legacy families run the pre-Fabric stack — the facts line is the one
        // place the stack is even named, and it must not lie about the loader.
        var legacy = group.Versions.Any(Core.Loaders.LegacyForgeInstaller.IsLegacy);
        var stackText = legacy ? "Forge · OptiFine" : "Fabric · Sodium · Iris";
        FactsText.Text = $"{group.Versions.Count} playable · {stackText}";
        UpdateActionState();
    }

    private void UpdateActionState()
    {
        var version = VersionDropdown.SelectedItem as string;
        LaunchButton.IsEnabled = version != null;
        ModsButton.IsEnabled = version != null;
        // Keep Home's selection in step with the pick as the user browses.
        if (version != null)
            VersionSelected?.Invoke(version);
    }

    // ---- events ----

    private void GridList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || GridList.SelectedItem is not VersionGroup group) return;
        ToDetailState(group);
    }

    private void StackList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncing || StackList.SelectedItem is not VersionGroup group) return;
        ShowGroupDetail(group);
    }

    private void VersionDropdown_SelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateActionState();

    private void Back_Click(object sender, RoutedEventArgs e)
    {
        _syncing = true;
        GridList.SelectedItem = null;   // so re-clicking the same card fires again
        _syncing = false;
        ToGridState(animate: true);
    }

    private void Launch_Click(object sender, RoutedEventArgs e)
    {
        if (VersionDropdown.SelectedItem is string version)
        {
            LaunchRequested?.Invoke(version);
            CloseOverlay();
        }
    }

    private void Mods_Click(object sender, RoutedEventArgs e)
    {
        var version = VersionDropdown.SelectedItem as string
                      ?? (DetailPanel.DataContext as VersionGroup)?.Newest;
        if (version == null) return;
        try
        {
            var mods = Core.Mods.ModManager.ModsFolder(version);
            Directory.CreateDirectory(mods);
            Process.Start(new ProcessStartInfo { FileName = mods, UseShellExecute = true });
        }
        catch
        {
            // Opening a folder is best-effort — never crash the picker over it.
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => CloseOverlay();

    // Render mode only (UI/RenderShots.cs): open the picker, optionally straight
    // into one family's detail view (index into VersionCatalog.Groups).
    internal void TourOpen(int detailIndex)
    {
        Show(null);
        if (detailIndex >= 0 && detailIndex < VersionCatalog.Groups.Count)
            GridList.SelectedItem = VersionCatalog.Groups[detailIndex];
    }

    internal void TourClose()
    {
        ++_sceneRevision;
        Visibility = Visibility.Collapsed;
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);
        if (e.Key != Key.Escape) return;
        if (DetailState.Visibility == Visibility.Visible) Back_Click(this, new RoutedEventArgs());
        else CloseOverlay();
        e.Handled = true;
    }
}
