using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

public partial class LaunchLoadingOverlay : UserControl
{
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
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        VersionText.Text = version;
        LoaderCaptionText.Text = loaderCaption;
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

        if (!SystemParameters.ClientAreaAnimation)
        {
            ProgressTranslate.X = 0;
            return;
        }

        var animation = new DoubleAnimation(0, 360, new Duration(TimeSpan.FromSeconds(2.5)))
        {
            RepeatBehavior = RepeatBehavior.Forever
        };
        _spinClock = animation.CreateClock();
        transform.ApplyAnimationClock(RotateTransform.AngleProperty, _spinClock);

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

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        // One shot: further clicks do nothing while the cancellation unwinds
        // (the launch flow hides the overlay from its own finally).
        CancelButton.IsEnabled = false;
        StageText.Text = "Cancelling";
        CancelRequested?.Invoke(this, EventArgs.Empty);
    }
}
