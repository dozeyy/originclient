using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.App.UI.Controls;

// The full-page loading scene: used for install+launch (Show) and for the
// launcher's own self-update (ShowUpdate), so both read as the same Origin
// moment — spinning mark, title, bar, stage line.
public partial class LaunchLoadingOverlay : UserControl
{
    // ProgressFill's width in the XAML: the sliding block in launch mode.
    private const double SweepWidth = 84;

    private AnimationClock? _spinClock;
    private AnimationClock? _progressClock;
    private long _sceneRevision;
    private long _stageRevision;

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
    }

    /// <summary>Raised when the player clicks Cancel; the launch flow that
    /// showed the overlay cancels its in-flight provisioning.</summary>
    public event EventHandler? CancelRequested;

    public void Show(string version, string loaderCaption)
    {
        EyebrowText.Text = "STARTING";
        CancelButton.Visibility = Visibility.Visible;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = SweepWidth;
        ShowScene(version, loaderCaption, sweep: true);
    }

    /// <summary>Self-update mode: same scene, no Cancel (updates are
    /// mandatory and the download is the only thing left to do), and a
    /// determinate fill fed by <see cref="ReportUpdate"/>.</summary>
    public void ShowUpdate(string newVersion)
    {
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

    private void ShowScene(string title, string caption, bool sweep)
    {
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
        Opacity = 1;

        if (SystemParameters.ClientAreaAnimation)
        {
            var duration = (Duration)FindResource("Motion.Scene");
            var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
            LoadingStage.BeginAnimation(OpacityProperty,
                new DoubleAnimation(0, 1, duration) { EasingFunction = ease },
                HandoffBehavior.SnapshotAndReplace);
            LoadingStageTranslate.BeginAnimation(TranslateTransform.YProperty,
                new DoubleAnimation(12, 0, duration) { EasingFunction = ease },
                HandoffBehavior.SnapshotAndReplace);
        }
        else
        {
            LoadingStage.Opacity = 1;
            LoadingStageTranslate.Y = 0;
        }

        var transform = new RotateTransform();
        Mark.RenderTransform = transform;

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
        transform.ApplyAnimationClock(RotateTransform.AngleProperty, _spinClock);

        if (!sweep)
            return;

        // Slide the fill across the track (start fully off-left and finish
        // fully off-right), easing at each end so it reads as
        // a smooth continuous sweep rather than a hard loop.
        var slide = new DoubleAnimation(-ProgressFill.Width, ProgressTrack.Width, new Duration(TimeSpan.FromSeconds(1.15)))
        {
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }
        };
        _progressClock = slide.CreateClock();
        ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, _progressClock);
    }

    public void Hide()
    {
        var sceneRevision = ++_sceneRevision;
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        _spinClock?.Controller?.Stop();
        _spinClock = null;
        _progressClock?.Controller?.Stop();
        _progressClock = null;

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

    public void ReportStage(string stage)
    {
        if (StageText.Text == stage) return;
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
        // (the launch flow hides the overlay from its own finally).
        CancelButton.IsEnabled = false;
        StageText.Text = "Cancelling";
        CancelRequested?.Invoke(this, EventArgs.Empty);
    }
}
