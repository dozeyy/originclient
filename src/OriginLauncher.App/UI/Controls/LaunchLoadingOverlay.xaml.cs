using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;

namespace OriginLauncher.App.UI.Controls;

public partial class LaunchLoadingOverlay : UserControl
{
    private AnimationClock? _spinClock;

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
    }

    public void Show(string version, string loaderCaption)
    {
        VersionText.Text = version;
        LoaderCaptionText.Text = loaderCaption;
        StageText.Text = "Preparing...";
        Visibility = Visibility.Visible;

        var transform = new RotateTransform();
        Mark.RenderTransform = transform;

        var animation = new DoubleAnimation(0, 360, new Duration(TimeSpan.FromSeconds(2.5)))
        {
            RepeatBehavior = RepeatBehavior.Forever
        };
        _spinClock = animation.CreateClock();
        transform.ApplyAnimationClock(RotateTransform.AngleProperty, _spinClock);
    }

    public void Hide()
    {
        Visibility = Visibility.Collapsed;
        _spinClock?.Controller?.Stop();
        _spinClock = null;
    }

    public void ReportStage(string stage) => StageText.Text = stage;
}
