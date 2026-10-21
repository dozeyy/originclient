using System.Windows;
using OriginLauncher.App.UI.Pages;
using Xunit;

namespace OriginLauncher.UiTests;

/// <summary>
/// Regression: Home -> open the version picker -> click Mods -> click Home left Home BLANK (Will 2026-10-20).
/// Cause: the picker's fade-out finished while Home was swapped out of the window, so Home's strip
/// (HomeCard) stayed faded to opacity 0 and non-hit-testable. Home now snaps its strip to the scenes' real
/// state on every re-attach. Runs inside CrashWindowFallbackTests' STA body because a process can have only
/// one Application and that test owns it. Pages are constructed, never shown.
/// </summary>
internal static class HomeSceneRegression
{
    internal static void Run()
    {
        // The picker decodes thumbnails via TaskScheduler.FromCurrentSynchronizationContext: give this STA thread one.
        SynchronizationContext.SetSynchronizationContext(new System.Windows.Threading.DispatcherSynchronizationContext(System.Windows.Threading.Dispatcher.CurrentDispatcher));
        var home = new HomePage();

        // Picker open: the strip steps away (opacity animates to 0), exactly as in the app.
        home.VersionOverlay.Show(null);
        Assert.True(home.VersionOverlay.IsOpen);

        // Nav click while open: MainWindow.NavigateTo closes the picker, then Home leaves the window.
        home.CloseVersionPicker();
        Assert.False(home.VersionOverlay.IsOpen); // closed at once, not mid-fade
        Assert.False(home.HasOpenScene);

        // Home comes back (Loaded -> SnapSceneState). The strip must be fully shown and clickable.
        home.SnapSceneStateForTest();
        Assert.Equal(1.0, home.HomeCard.Opacity);
        Assert.Equal(1.0, home.BannerSlot.Opacity);
        Assert.True(home.HomeCard.IsHitTestVisible);
        Assert.Equal(0.0, home.StripShift.Y);
        Assert.Equal(Visibility.Collapsed, home.VersionOverlay.Visibility);

        // Snap is a pure function of the scenes: with the picker open it hides the strip, closed it shows it.
        home.VersionOverlay.Show(null);
        home.SnapSceneStateForTest();
        Assert.Equal(0.0, home.HomeCard.Opacity);
        Assert.False(home.HomeCard.IsHitTestVisible);
        home.CloseVersionPicker();
        home.SnapSceneStateForTest();
        Assert.Equal(1.0, home.HomeCard.Opacity);
    }
}
