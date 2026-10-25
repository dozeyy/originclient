using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Updates;

namespace OriginLauncher.App.UI.Controls;

// The full-page progress screen: used for install+launch (Show) and for the
// launcher's own self-update (ShowUpdate), so both read as the same Origin
// moment — spinning mark, title, bar, stage line. In a launch it stays up
// through Minecraft's start and lowers (Hide) once the game's own window is up
// and showing its loading screen (Core/Launch/GameHandoff decides when).
public partial class LaunchLoadingOverlay : UserControl
{
    // ProgressSweep's width in the XAML: the sliding block of an unmeasured stage.
    private const double SweepWidth = 96;
    // Glide between the ~8-10/s progress reports instead of stepping.
    private static readonly Duration FillGlide = new(TimeSpan.FromMilliseconds(220));

    private AnimationClock? _spinClock;
    private AnimationClock? _sweepClock;
    private long _sceneRevision;
    private long _stageRevision;
    // Launch mode reads real counts out of stage lines; update mode's fill is ReportUpdate's.
    private bool _launchMode;
    // The big line when no stage has a real count: the version starting (or updating to).
    private string _bigFallback = "";

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
    }

    /// <summary>
    /// The fill as shown, 0..1: one animated value drives the bar's scale AND the
    /// count, so the number rolls up in step with the bar instead of jumping between
    /// the ~8-10/s progress reports.
    /// </summary>
    private static readonly DependencyProperty ShownProperty = DependencyProperty.Register(
        "Shown", typeof(double), typeof(LaunchLoadingOverlay),
        new PropertyMetadata(0.0, (d, e) => ((LaunchLoadingOverlay)d).OnShown((double)e.NewValue)));

    private bool _counting;

    private void OnShown(double value)
    {
        ProgressScale.ScaleX = value;
        if (_counting) VersionText.Text = ((int)Math.Floor(value * 100 + 1e-6)).ToString();
    }

    // Sets the shown fill with no glide (a new scene, the sweep handing over, reduced motion).
    private void SetShown(double value)
    {
        BeginAnimation(ShownProperty, null);
        SetValue(ShownProperty, value);
        OnShown(value);
    }

    // The % sits on the count's baseline: lift it by the difference in how far each size reaches below its baseline.
    // Measured only once the % is visible (a collapsed element measures to nothing, which left it floating).
    private void AlignPercentToBaseline()
    {
        var infinite = new Size(double.PositiveInfinity, double.PositiveInfinity);
        VersionText.Measure(infinite);
        PercentText.Measure(infinite);
        var below = (VersionText.DesiredSize.Height - VersionText.Baseline)
                    - (PercentText.DesiredSize.Height - PercentText.Baseline);
        PercentText.Margin = new Thickness(PercentGap, 0, 0, Math.Max(0, below));
    }

    private const double PercentGap = 6;
    private bool _percentAligned;

    private void ShowCount(bool counting)
    {
        _counting = counting;
        PercentText.Visibility = counting ? Visibility.Visible : Visibility.Collapsed;
        if (counting && !_percentAligned)
        {
            AlignPercentToBaseline();
            _percentAligned = true;
        }
        if (!counting) VersionText.Text = _bigFallback;
    }

    /// <summary>Raised when the player clicks Cancel; the launch flow that
    /// showed the overlay cancels its in-flight provisioning.</summary>
    public event EventHandler? CancelRequested;

    /// <summary>The Play press. No loader name anywhere: the player never sees
    /// which loader a version runs on (mandate 1).</summary>
    public void Show(string version, string? caption = null)
    {
        _launchMode = true;
        EyebrowText.Text = $"PREPARING MINECRAFT {version}";
        CancelButton.Visibility = Visibility.Visible;
        CancelButton.ToolTip = null;
        LoaderCaptionText.Visibility = Visibility.Collapsed;
        CaptionFamily.Text = FamilyTheme.Of(version).Label;
        ProgressFill.SetResourceReference(BackgroundProperty, "Brush.Accent");
        ProgressSweep.SetResourceReference(BackgroundProperty, "Brush.Accent");
        CaptionDetail.Text = caption ?? "";
        CaptionDetail.Visibility = string.IsNullOrEmpty(caption) ? Visibility.Collapsed : Visibility.Visible;
        CaptionPanel.Visibility = Visibility.Visible;
        ShowScene(version);
        ShowProgress(null);
    }

    /// <summary>Self-update mode: same scene, no Cancel (updates are
    /// mandatory and the download is the only thing left to do), and a
    /// determinate fill fed by <see cref="ReportUpdate"/>.</summary>
    public void ShowUpdate(string newVersion)
    {
        _launchMode = false;
        ProgressFill.SetResourceReference(BackgroundProperty, "Brush.Accent");
        ProgressSweep.SetResourceReference(BackgroundProperty, "Brush.Accent");
        EyebrowText.Text = $"UPDATING ORIGIN LAUNCHER TO {newVersion}";
        CancelButton.Visibility = Visibility.Collapsed;
        LoaderCaptionText.Text = "Getting the newest version";
        LoaderCaptionText.Visibility = Visibility.Visible;
        CaptionPanel.Visibility = Visibility.Collapsed;
        ShowScene(newVersion);
        ShowProgress(0);
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
        if (fraction is { } f) ShowProgress(f);
    }

    /// <summary>The game process is up: Cancel now stops that starting game.</summary>
    public void EnterGameStart()
    {
        CancelButton.ToolTip = "Stop Minecraft starting";
    }

    private void ShowScene(string title)
    {
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        _bigFallback = title;
        VersionText.Text = title;
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

        // The bar starts empty and bare; Show/ShowUpdate then pick fill or sweep.
        StopClock(ref _sweepClock);
        ProgressSweep.Visibility = Visibility.Collapsed;
        ProgressFill.Visibility = Visibility.Collapsed;
        ShowCount(false);
        SetShown(0);

        // The mark is the loading indicator: it breathes (a slow opacity pulse). The drawing itself is never rotated
        // or scaled (design system 1.3). Reduced motion leaves it still.
        StopClock(ref _spinClock);
        if (!SystemParameters.ClientAreaAnimation) return;
        var pulse = new DoubleAnimation(1, 0.45, new Duration(TimeSpan.FromSeconds(1.2)))
        {
            AutoReverse = true,
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut },
        };
        _spinClock = pulse.CreateClock();
        Mark.ApplyAnimationClock(OpacityProperty, _spinClock);
    }

    /// <summary>
    /// The bar. A real <paramref name="fraction"/> (0..1) fills to it, gliding
    /// between the reports; null (a stage with nothing measurable) goes to the
    /// sliding block. Never a made-up percentage (StageProgress).
    /// </summary>
    private void ShowProgress(double? fraction)
    {
        if (fraction is not { } f)
        {
            ShowCount(false);
            if (ProgressSweep.Visibility == Visibility.Visible) return;
            ProgressFill.Visibility = Visibility.Collapsed;
            SetShown(0);
            ProgressSweep.Visibility = Visibility.Visible;
            StartSweep();
            return;
        }

        f = Math.Clamp(f, 0, 1);
        var fresh = !_counting;
        ShowCount(true);
        if (ProgressSweep.Visibility != Visibility.Collapsed)
        {
            StopClock(ref _sweepClock);
            ProgressSweep.Visibility = Visibility.Collapsed;
            SetShown(0);
        }
        else if (fresh) OnShown((double)GetValue(ShownProperty));
        ProgressFill.Visibility = Visibility.Visible;
        if (!SystemParameters.ClientAreaAnimation || Motion.Off)
        {
            SetShown(f);
            return;
        }
        BeginAnimation(ShownProperty,
            new DoubleAnimation(f, FillGlide) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } },
            HandoffBehavior.SnapshotAndReplace);
    }

    // Slide the block across the track (start fully off-left, finish fully
    // off-right), easing at each end so it reads as a smooth continuous sweep.
    // Reduced motion parks it at the left edge.
    private void StartSweep()
    {
        StopClock(ref _sweepClock);
        ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, null);
        ProgressTranslate.X = 0;
        if (!SystemParameters.ClientAreaAnimation) return;
        var slide = new DoubleAnimation(-SweepWidth, ProgressTrack.Width, new Duration(TimeSpan.FromSeconds(1.15)))
        {
            RepeatBehavior = RepeatBehavior.Forever,
            EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }
        };
        _sweepClock = slide.CreateClock();
        ProgressTranslate.ApplyAnimationClock(TranslateTransform.XProperty, _sweepClock);
    }

    private static void StopClock(ref AnimationClock? clock)
    {
        clock?.Controller?.Stop();
        clock = null;
    }

    /// <summary>Fades the screen out. Home is clickable from the moment it starts leaving.</summary>
    public void Hide()
    {
        var sceneRevision = ++_sceneRevision;
        ++_stageRevision;
        StageText.BeginAnimation(OpacityProperty, null);
        IsHitTestVisible = false;
        StopClock(ref _spinClock);
        StopClock(ref _sweepClock);

        if (!SystemParameters.ClientAreaAnimation || Visibility != Visibility.Visible)
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
