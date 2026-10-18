using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.App.UI.Controls;

// The full-page loading scene: used for install+launch (Show) and for the
// launcher's own self-update (ShowUpdate), so both read as the same Origin
// moment — spinning mark, title, bar, stage line. In a launch it stays up
// through Minecraft's start and leaves with Hide(handedOff: true) once the
// game draws its own Origin loading screen (Core/Launch/GameHandoff).
public partial class LaunchLoadingOverlay : UserControl
{
    // ProgressFill's width in the XAML: the sliding block in launch mode.
    private const double SweepWidth = 84;

    private AnimationClock? _spinClock;
    private AnimationClock? _progressClock;
    private long _sceneRevision;
    private long _stageRevision;
    // Launch mode reads real counts out of stage lines; update mode's fill is ReportUpdate's.
    private bool _launchMode;
    // The fill shows a real count (StageProgress) rather than the sweep.
    private bool _determinate;
    private readonly ScaleTransform _markScale = new();
    private readonly RotateTransform _markRotate = new();

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
        Mark.RenderTransform = new TransformGroup { Children = { _markScale, _markRotate } };
    }

    /// <summary>Raised when the player clicks Cancel; the launch flow that
    /// showed the overlay cancels its in-flight provisioning.</summary>
    public event EventHandler? CancelRequested;

    public void Show(string version, string loaderCaption)
    {
        _launchMode = true;
        EyebrowText.Text = "STARTING";
        CancelButton.Visibility = Visibility.Visible;
        CancelButton.ToolTip = null;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = SweepWidth;
        ShowScene(version, loaderCaption, sweep: true);
    }

    /// <summary>Self-update mode: same scene, no Cancel (updates are
    /// mandatory and the download is the only thing left to do), and a
    /// determinate fill fed by <see cref="ReportUpdate"/>.</summary>
    public void ShowUpdate(string newVersion)
    {
        _launchMode = false;
        EyebrowText.Text = "UPDATING";
        CancelButton.Visibility = Visibility.Collapsed;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = 0;
        ShowScene($"Origin Launcher {newVersion}", "Getting the newest version", sweep: false);
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
        var target = progress.Stage == UpdateService.UpdateStage.Downloading
            ? progress.Fraction * ProgressTrack.Width
            : ProgressTrack.Width;
        if (target is not { } width) return;

        if (!SystemParameters.ClientAreaAnimation)
        {
            ProgressFill.BeginAnimation(WidthProperty, null);
            ProgressFill.Width = width;
            return;
        }
        // Glide between the ~10/s reports instead of stepping.
        ProgressFill.BeginAnimation(WidthProperty,
            new DoubleAnimation(width, TimeSpan.FromMilliseconds(120)),
            HandoffBehavior.SnapshotAndReplace);
    }

    /// <summary>The game process is up: Cancel now stops that starting game.</summary>
    public void EnterGameStart()
    {
        CancelButton.ToolTip = "Stop Minecraft starting";
    }

    private void ShowScene(string title, string caption, bool sweep)
    {
        // A Show during a previous Hide's fade takes the scene back: that
        // fade's clocks stop here rather than at its (now stale) completion.
        StopClocks();
        _determinate = false;
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        VersionText.Text = title;
        LoaderCaptionText.Text = caption;
        StageText.Text = "Preparing";
        StageText.Opacity = 1;
        CancelButton.IsEnabled = true;
        ++_sceneRevision;
        Visibility = Visibility.Visible;
        IsHitTestVisible = true;

        BeginAnimation(OpacityProperty, null);
        LoadingStage.BeginAnimation(OpacityProperty, null);
        LoadingStageTranslate.BeginAnimation(TranslateTransform.YProperty, null);
        _markScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
        _markScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
        Opacity = 1;
        LoadingStage.Opacity = 1;
        LoadingStageTranslate.Y = 0;
        _markScale.ScaleX = _markScale.ScaleY = 1;

        if (SystemParameters.ClientAreaAnimation)
        {
            // The Play press (docs/MOTION.md "play"): the cover dissolves in
            // over Home, the mark grows into focus and assembles, then the
            // text settles under it. Opacity and transforms only — no layout,
            // no blur — and every target is the resting value, so the scene is
            // complete even if a clock never ticks. It is hit-testable from
            // the first frame: nothing can click through to Home meanwhile.
            var scene = (Duration)FindResource("Motion.Scene");
            var handoff = (Duration)FindResource("Motion.Handoff");
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, scene) { EasingFunction = ease, FillBehavior = FillBehavior.Stop },
                HandoffBehavior.SnapshotAndReplace);
            var grow = new DoubleAnimation(0.86, 1, handoff) { EasingFunction = ease, FillBehavior = FillBehavior.Stop };
            _markScale.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
            _markScale.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
            Mark.Align((Duration)FindResource("Motion.Intro"));
            var settle = TimeSpan.FromTicks(scene.TimeSpan.Ticks / 2);
            LoadingStage.BeginAnimation(OpacityProperty,
                OriginMark.Hold(0, 1, settle, scene.TimeSpan), HandoffBehavior.SnapshotAndReplace);
            LoadingStageTranslate.BeginAnimation(TranslateTransform.YProperty,
                OriginMark.Hold(12, 0, settle, scene.TimeSpan), HandoffBehavior.SnapshotAndReplace);
        }

        _markRotate.BeginAnimation(RotateTransform.AngleProperty, null);
        _markRotate.Angle = 0;

        // The sliding sweep and a determinate fill share ProgressFill; the
        // fill needs it parked at the track's left edge.
        _progressClock?.Controller?.Stop();
        _progressClock = null;
        ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, null);
        ProgressTranslate.X = 0;

        if (!SystemParameters.ClientAreaAnimation)
            return;

        var animation = new DoubleAnimation(0, 360, new Duration(TimeSpan.FromSeconds(2.5)))
        {
            RepeatBehavior = RepeatBehavior.Forever
        };
        _spinClock = animation.CreateClock();
        _markRotate.ApplyAnimationClock(RotateTransform.AngleProperty, _spinClock);

        if (sweep)
            StartSweep();
    }

    // Slide the fill across the track (start fully off-left and finish
    // fully off-right), easing at each end so it reads as
    // a smooth continuous sweep rather than a hard loop.
    private void StartSweep()
    {
        _progressClock?.Controller?.Stop();
        _progressClock = null;
        if (!SystemParameters.ClientAreaAnimation)
            return;
        var slide = new DoubleAnimation(-ProgressFill.Width, ProgressTrack.Width, new Duration(TimeSpan.FromSeconds(1.15)))
        {
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }
        };
        _progressClock = slide.CreateClock();
        ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, _progressClock);
    }

    private void StopClocks()
    {
        _spinClock?.Controller?.Stop();
        _spinClock = null;
        _progressClock?.Controller?.Stop();
        _progressClock = null;
    }

    /// <summary>
    /// Lowers the scene. <paramref name="handedOff"/>: the game is now showing
    /// its own Origin loading screen, so the scene leaves the way the game's
    /// takes over — a slower dissolve while the mark grows slightly towards
    /// the player — instead of the quick exit used for cancel/failure/done.
    /// Home is clickable from the moment the scene starts leaving; the clocks
    /// keep running until it is gone (stopping the spin first would snap the
    /// mark upright mid-fade) and then stop, so nothing ticks behind Home.
    /// </summary>
    public void Hide(bool handedOff = false)
    {
        var sceneRevision = ++_sceneRevision;
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        IsHitTestVisible = false;

        if (!SystemParameters.ClientAreaAnimation)
        {
            StopClocks();
            Visibility = Visibility.Collapsed;
            return;
        }

        var duration = (Duration)FindResource(handedOff ? "Motion.Handoff" : "Motion.Exit");
        var fade = new DoubleAnimation(0, duration) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
        fade.Completed += (_, _) =>
        {
            if (_sceneRevision != sceneRevision) return;
            StopClocks();
            Visibility = Visibility.Collapsed;
        };
        if (handedOff)
        {
            var grow = new DoubleAnimation(1.08, duration) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
            _markScale.BeginAnimation(ScaleTransform.ScaleXProperty, grow, HandoffBehavior.SnapshotAndReplace);
            _markScale.BeginAnimation(ScaleTransform.ScaleYProperty, grow, HandoffBehavior.SnapshotAndReplace);
        }
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
    /// Launch mode's bar: a stage with a real count fills to it (gliding
    /// between the ~8/s reports); a stage without one goes back to the
    /// sweep. Never a made-up percentage (StageProgress).
    /// </summary>
    private void ShowProgress(double? fraction)
    {
        if (fraction is { } f)
        {
            if (!_determinate)
            {
                _determinate = true;
                _progressClock?.Controller?.Stop();
                _progressClock = null;
                ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, null);
                ProgressTranslate.X = 0;
                ProgressFill.BeginAnimation(WidthProperty, null);
                ProgressFill.Width = 0;
            }
            var width = f * ProgressTrack.Width;
            if (!SystemParameters.ClientAreaAnimation)
            {
                ProgressFill.BeginAnimation(WidthProperty, null);
                ProgressFill.Width = width;
                return;
            }
            ProgressFill.BeginAnimation(WidthProperty,
                new DoubleAnimation(width, TimeSpan.FromMilliseconds(120)), HandoffBehavior.SnapshotAndReplace);
        }
        else if (_determinate)
        {
            _determinate = false;
            ProgressFill.BeginAnimation(WidthProperty, null);
            ProgressFill.Width = SweepWidth;
            ProgressTranslate.X = 0;
            StartSweep();
        }
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
