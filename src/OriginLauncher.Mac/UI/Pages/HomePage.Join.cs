using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;

namespace OriginLauncher.Mac.UI.Pages;

// The Join card (twin of the WPF HomePage ApplyProfile / FitStrip / FoldJoin): Home shows no profile switcher (the
// profile is picked on the Profiles page only); the active profile decides Play's mods and, when it has a server, the
// Join card between My mods and Play. A profile only APPLIES to its own version (ProfileRules.Active).
public sealed partial class HomePage
{
    private ModProfile? _joinProfile;
    private bool _joinFolded;
    private double _joinFullWidth = double.NaN;

    private void ApplyProfile(LauncherSettings settings)
    {
        var active = ProfileRules.Active(settings.ModProfiles, settings.SelectedProfileId, _selectedVersion);
        _joinProfile = active is { HasServer: true } ? active : null;
        JoinButton.IsVisible = _joinProfile != null;
        if (_joinProfile != null)
        {
            JoinServerText.Text = ProfileRules.ServerLabel(_joinProfile) ?? "Server";
            Avalonia.Automation.AutomationProperties.SetName(JoinButton, ProfileRules.JoinLabel(_joinProfile));
        }
        FitStrip(contentChanged: true);
    }

    private async void JoinButton_Click(object? sender, RoutedEventArgs e)
    {
        if (_joinProfile is { HasServer: true } profile)
            await JoinServerAsync(profile.Version, profile.ServerAddress!);
    }

    // Short of width, Join folds to its server icon so the version card, My mods and Play keep their full size.
    private void FitStrip(bool contentChanged = false)
    {
        // The page's width less the strip's side margins (HomeCard may not be arranged at the new width yet).
        var width = Bounds.Width - HomeCard.Margin.Left - HomeCard.Margin.Right;
        if (width <= 0) return;
        var infinite = new Size(double.PositiveInfinity, double.PositiveInfinity);
        double Natural(Control e)
        {
            if (!e.IsVisible) return 0;
            e.Measure(infinite);
            return e.DesiredSize.Width; // includes the element's margin
        }

        // Join's full width is read with its words showing: unfold it only when its server changed.
        if (contentChanged) _joinFullWidth = double.NaN;
        if (double.IsNaN(_joinFullWidth))
        {
            FoldJoin(false);
            _joinFullWidth = Natural(JoinButton);
        }
        // The left column's 24 margin to the right group is part of the row.
        var fixedWidth = Natural(VersionButton) + 24 + Natural(MyModsSwitch) + Natural(PlayButton);
        FoldJoin(JoinButton.IsVisible && fixedWidth + _joinFullWidth > width);
    }

    // Join as its server icon alone, centred in a 64 square; "Join <server>" becomes the tooltip.
    private void FoldJoin(bool fold)
    {
        if (fold == _joinFolded) return;
        _joinFolded = fold;
        JoinWords.IsVisible = !fold;
        if (fold)
        {
            JoinButton.Width = this.ThemeDouble("Size.StripControl");
            JoinButton.Padding = new Thickness(0);
            JoinIcon.Margin = new Thickness(0);
            ToolTip.SetTip(JoinButton, Avalonia.Automation.AutomationProperties.GetName(JoinButton));
        }
        else
        {
            JoinButton.ClearValue(WidthProperty);
            JoinButton.Padding = new Thickness(14, 0, 14, 0);
            JoinIcon.Margin = new Thickness(0, 0, 12, 0);
            ToolTip.SetTip(JoinButton, null);
        }
    }

    /// <summary>Render shots only: Home as if <paramref name="profile"/> were selected (no settings are touched).</summary>
    internal void TourShowProfile(ModProfile? profile)
    {
        if (profile == null)
        {
            ApplyProfile(new LauncherSettings());
            return;
        }
        TourSelectVersion(profile.Version);
        ApplyProfile(new LauncherSettings { ModProfiles = { profile }, SelectedProfileId = profile.Id });
        JoinButton.IsEnabled = true;
    }

    internal bool TourJoinFolded => _joinFolded;
}
