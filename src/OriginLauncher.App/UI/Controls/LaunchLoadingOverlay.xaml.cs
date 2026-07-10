using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

public partial class LaunchLoadingOverlay : UserControl
{
    private AnimationClock? _spinClock;
    private AnimationClock? _progressClock;

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
    }

    /// <summary>Raised when the player clicks Cancel; the launch flow that
    /// showed the overlay cancels its in-flight provisioning.</summary>
    public event EventHandler? CancelRequested;

    public void Show(string version, string loaderCaption)
    {
        VersionText.Text = version;
        LoaderCaptionText.Text = loaderCaption;
        StageText.Text = "Preparing...";
        CancelButton.IsEnabled = true;
        Visibility = Visibility.Visible;

        var transform = new RotateTransform();
        Mark.RenderTransform = transform;

        var animation = new DoubleAnimation(0, 360, new Duration(TimeSpan.FromSeconds(2.5)))
        {
            RepeatBehavior = RepeatBehavior.Forever
        };
        _spinClock = animation.CreateClock();
        transform.ApplyAnimationClock(RotateTransform.AngleProperty, _spinClock);

        // Slide the 70px fill across the 240px track (start off-left at -70,
        // end off-right at the track width), easing at each end so it reads as
        // a smooth continuous sweep rather than a hard loop.
        var slide = new DoubleAnimation(-70, 240, new Duration(TimeSpan.FromSeconds(1.15)))
        {
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }
        };
        _progressClock = slide.CreateClock();
        ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, _progressClock);
    }

    public void Hide()
    {
        Visibility = Visibility.Collapsed;
        _spinClock?.Controller?.Stop();
        _spinClock = null;
        _progressClock?.Controller?.Stop();
        _progressClock = null;
    }

    public void ReportStage(string stage) => StageText.Text = stage;

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        // One shot: further clicks do nothing while the cancellation unwinds
        // (the launch flow hides the overlay from its own finally).
        CancelButton.IsEnabled = false;
        StageText.Text = "Cancelling...";
        CancelRequested?.Invoke(this, EventArgs.Empty);
    }
}
