using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.App.UI.Controls;

// The full-page loading scene: used for install+launch (Show) and for the
// launcher's own self-update (ShowUpdate), so both read as the same Origin
// moment. The scene is the game's own loading screen (LoadingScene). In a
// launch it stays up through Minecraft's start: with a game that announces its
// window it lifts out of the launcher and glides onto that window
// (MoveToGameWindow), otherwise it dissolves with Hide(handedOff: true) once
// the game draws its own Origin loading screen (Core/Launch/GameHandoff).
public partial class LaunchLoadingOverlay : UserControl
{
    // The Play button's mark, if the press didn't come from a button.
    private const double CentreMarkSize = 72;

    private long _sceneRevision;
    private long _stageRevision;
    // Launch mode reads real counts out of stage lines; update mode's fill is ReportUpdate's.
    private bool _launchMode;
    private SceneHandoffWindow? _handoff;

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
    }

    /// <summary>Raised when the player clicks Cancel; the launch flow that
    /// showed the overlay cancels its in-flight provisioning.</summary>
    public event EventHandler? CancelRequested;

    /// <summary>
    /// The Play press: the scene's rings spin out of <paramref name="from"/>
    /// (the Play button) into the loading orbit. No loader name anywhere: the
    /// player never sees which loader a version runs on (mandate 1).
    /// </summary>
    public void Show(string version, FrameworkElement? from = null)
    {
        _launchMode = true;
        EyebrowText.Text = "STARTING";
        CancelButton.Visibility = Visibility.Visible;
        CancelButton.ToolTip = null;
        LoaderCaptionText.Visibility = Visibility.Collapsed;
        Scene.Progress = null;
        ShowScene(version, from);
    }

    /// <summary>Self-update mode: same scene, no Cancel (updates are
    /// mandatory and the download is the only thing left to do), and a
    /// determinate fill fed by <see cref="ReportUpdate"/>.</summary>
    public void ShowUpdate(string newVersion)
    {
        _launchMode = false;
        EyebrowText.Text = "UPDATING";
        CancelButton.Visibility = Visibility.Collapsed;
        LoaderCaptionText.Text = "Getting the newest version";
        LoaderCaptionText.Visibility = Visibility.Visible;
        Scene.Progress = 0;
        ShowScene($"Origin Launcher {newVersion}", from: null);
    }

    /// <summary>Moves the update fill and stage line. A report without a
    /// known size keeps the bar where it is and updates only the text.</summary>
    public void ReportUpdate(UpdateService.UpdateProgress progress)
    {
        // Byte ticks arrive ~10/s; ReportStage's fade-out/fade-in would restart
        // on every tick and leave the line invisible. Only a stage change
        // (Preparing -> Downloading -> Installing) fades; ticks swap in place.
        if (progress.Stage == UpdateService.UpdateStage.Downloading && progress.ReceivedBytes > 0)
            SetStageImmediately(progress.Describe());
        else
            ReportStage(progress.Describe());
        var fraction = progress.Stage == UpdateService.UpdateStage.Downloading ? progress.Fraction : 1.0;
        if (fraction is { } f) Scene.Progress = f; // the scene eases it, like the game's bar
    }

    /// <summary>The game process is up: Cancel now stops that starting game.</summary>
    public void EnterGameStart()
    {
        CancelButton.ToolTip = "Stop Minecraft starting";
    }

    private void ShowScene(string title, FrameworkElement? from)
    {
        // A Show during a previous Hide's fade takes the scene back; a scene
        // still out on a game window from an earlier launch goes at once.
        _handoff?.FadeOutAndClose(new Duration(TimeSpan.Zero));
        _handoff = null;
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        VersionText.Text = title;
        StageText.Text = "Preparing";
        StageText.Opacity = 1;
        CancelButton.IsEnabled = true;
        ++_sceneRevision;
        Visibility = Visibility.Visible;
        IsHitTestVisible = true;
        Scene.Visibility = Visibility.Visible;

        BeginAnimation(OpacityProperty, null);
        LoadingStage.BeginAnimation(OpacityProperty, null);
        LoadingStageTranslate.BeginAnimation(TranslateTransform.YProperty, null);
        Opacity = 1;
        LoadingStage.Opacity = 1;
        LoadingStageTranslate.Y = 0;

        // The Play press (docs/MOTION.md "play"): the rings start as a mark on
        // the button and spin out into the orbit while the charcoal comes up
        // behind them; the launcher's lines settle in as the rings land. Every
        // animation ends on the resting value, so the scene is complete even if
        // a clock never ticks.
        UpdateLayout();
        var size = CentreMarkSize;
        var origin = new Point(ActualWidth / 2, ActualHeight / 2);
        if (from is { IsVisible: true, ActualWidth: > 0 })
        {
            origin = from.TranslatePoint(new Point(from.ActualWidth / 2, from.ActualHeight / 2), Scene);
            size = Math.Min(from.ActualHeight * 1.6, from.ActualWidth);
        }
        Scene.Assemble(origin, size);

        if (!SystemParameters.ClientAreaAnimation) return;
        var assemble = TimeSpan.FromMilliseconds(LoadingScene.AssembleMs);
        var settle = TimeSpan.FromTicks(assemble.Ticks * 6 / 10);
        LoadingStage.BeginAnimation(OpacityProperty,
            OriginMark.Hold(0, 1, settle, assemble), HandoffBehavior.SnapshotAndReplace);
        LoadingStageTranslate.BeginAnimation(TranslateTransform.YProperty,
            OriginMark.Hold(12, 0, settle, assemble), HandoffBehavior.SnapshotAndReplace);
    }

    /// <summary>The scene's centre in screen pixels, or null when it isn't on screen.</summary>
    public (int X, int Y)? ScreenCentre()
    {
        try
        {
            if (!IsVisible || PresentationSource.FromVisual(this) == null) return null;
            var c = PointToScreen(new Point(ActualWidth / 2, ActualHeight / 2));
            return ((int)Math.Round(c.X), (int)Math.Round(c.Y));
        }
        catch (InvalidOperationException)
        {
            return null;
        }
    }

    /// <summary>
    /// The game opened its (still invisible) window at <paramref name="rect"/>:
    /// lift the scene out of the launcher and glide it onto that rect, where the
    /// window will fade in over the same picture. Does nothing (the scene stays
    /// and dissolves at Hide) when it can't be seen anyway: launcher minimised,
    /// reduced motion, or already lifted.
    /// </summary>
    public void MoveToGameWindow(GameWindowRect rect)
    {
        if (_handoff != null || Visibility != Visibility.Visible || !IsVisible || !SystemParameters.ClientAreaAnimation)
            return;
        if (Window.GetWindow(this) is not { WindowState: not WindowState.Minimized } owner) return;
        if (PresentationSource.FromVisual(this)?.CompositionTarget is not { } target) return;
        try
        {
            var dpd = target.TransformToDevice.M11 > 0 ? target.TransformToDevice.M11 : 1;
            var topLeft = PointToScreen(new Point(0, 0));
            var bottomRight = PointToScreen(new Point(ActualWidth, ActualHeight));
            var from = new Rect(topLeft.X / dpd, topLeft.Y / dpd,
                (bottomRight.X - topLeft.X) / dpd, (bottomRight.Y - topLeft.Y) / dpd);
            var to = new Rect(rect.X / dpd, rect.Y / dpd, rect.Width / dpd, rect.Height / dpd);
            var scale = rect.GuiScale > 0 ? rect.GuiScale : LoadingScene.AutoGuiScale(rect.Width, rect.Height);
            var toLayout = (scale / dpd, Math.Ceiling(rect.Width / (double)scale), Math.Ceiling(rect.Height / (double)scale));

            var handoff = new SceneHandoffWindow(owner, from, Scene.CurrentLayout(), to, toLayout, scale,
                Scene.Progress, (Duration)FindResource("Motion.Intro"));
            _handoff = handoff;
            var sceneRevision = _sceneRevision;
            handoff.ContentRendered += (_, _) =>
            {
                if (!ReferenceEquals(_handoff, handoff) || _sceneRevision != sceneRevision) return;
                // The copy is on screen exactly over ours: ours goes, the copy glides,
                // and Home shows round it as it travels.
                Scene.Visibility = Visibility.Hidden;
                IsHitTestVisible = false;
                handoff.Glide();
                var fade = new DoubleAnimation(0, (Duration)FindResource("Motion.Scene"));
                fade.Completed += (_, _) =>
                {
                    if (_sceneRevision == sceneRevision) Visibility = Visibility.Collapsed;
                };
                LoadingStage.BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
            };
            handoff.Show();
        }
        catch (Exception ex)
        {
            // Purely visual: the scene simply stays and dissolves at Hide.
            System.Diagnostics.Debug.WriteLine($"[LaunchLoadingOverlay] window handoff skipped: {ex}");
            _handoff?.FadeOutAndClose(new Duration(TimeSpan.Zero));
            _handoff = null;
            Scene.Visibility = Visibility.Visible;
        }
    }

    /// <summary>
    /// Lowers the scene. <paramref name="handedOff"/>: the game is now showing
    /// its own Origin loading screen, so the scene leaves at the handoff's pace
    /// (a slower dissolve; out on the game window, the copy dissolves to reveal
    /// the identical game underneath) instead of the quick exit used for
    /// cancel/failure/done. Home is clickable from the moment it starts leaving.
    /// </summary>
    public void Hide(bool handedOff = false)
    {
        var sceneRevision = ++_sceneRevision;
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        IsHitTestVisible = false;
        var duration = (Duration)FindResource(handedOff ? "Motion.Handoff" : "Motion.Exit");

        _handoff?.FadeOutAndClose(duration);
        _handoff = null;

        if (!SystemParameters.ClientAreaAnimation || Visibility != Visibility.Visible)
        {
            Visibility = Visibility.Collapsed;
            return;
        }

        var fade = new DoubleAnimation(0, duration) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
        fade.Completed += (_, _) =>
        {
            if (_sceneRevision != sceneRevision) return;
            Visibility = Visibility.Collapsed;
        };
        BeginAnimation(OpacityProperty, fade, HandoffBehavior.SnapshotAndReplace);
    }

    public void ReportStage(string stage)
    {
        var progress = _launchMode ? StageProgress.Parse(stage) : new StageProgress(stage, null);
        if (_launchMode) ShowProgress(progress.Fraction);
        if (StageText.Text == stage) return;
        // A count ticking inside one stage ("Preparing game files (12/340)")
        // swaps in place; the fade below is for a new stage only, or ~8 ticks
        // a second would keep the line faded out.
        if (progress.Fraction != null && StageProgress.Parse(StageText.Text).Label == progress.Label)
        {
            SetStageImmediately(stage);
            return;
        }
        var stageRevision = ++_stageRevision;

        if (!SystemParameters.ClientAreaAnimation || Visibility != Visibility.Visible)
        {
            StageText.Text = stage;
            StageText.Opacity = 1;
            return;
        }

        var fadeOut = new DoubleAnimation(0, (Duration)FindResource("Motion.Exit"));
        fadeOut.Completed += (_, _) =>
        {
            if (_stageRevision != stageRevision) return;
            StageText.Text = stage;
            StageText.BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, (Duration)FindResource("Motion.Base")),
                HandoffBehavior.SnapshotAndReplace);
        };
        StageText.BeginAnimation(OpacityProperty, fadeOut, HandoffBehavior.SnapshotAndReplace);
    }

    /// <summary>
    /// Launch mode's bar: a stage with a real count fills to it (the scene
    /// eases between the ~8/s reports, as the game's bar does); a stage
    /// without one goes back to the game's sweep. Never a made-up percentage
    /// (StageProgress).
    /// </summary>
    private void ShowProgress(double? fraction)
    {
        Scene.Progress = fraction;
        if (_handoff != null) _handoff.Progress = fraction;
    }

    private void SetStageImmediately(string stage)
    {
        ++_stageRevision; // supersedes any fade still in flight
        StageText.BeginAnimation(OpacityProperty, null);
        StageText.Opacity = 1;
        StageText.Text = stage;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        // One shot: further clicks do nothing while the cancellation unwinds
        // (the launch flow hides the overlay once it has: from its finally
        // while preparing, from the boot watch once the game was stopped).
        CancelButton.IsEnabled = false;
        StageText.Text = "Cancelling";
        CancelRequested?.Invoke(this, EventArgs.Empty);
    }
}
