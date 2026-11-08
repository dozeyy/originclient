using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.UI;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The launch cover (the Windows launcher's LaunchLoadingOverlay): over the whole window while a launch prepares, the
// art behind it under the launch scrim (MainWindow.OpenCover; ArtStage hides the numeral). Centred, 520 wide: the
// logo file (a slow opacity breath, still in render runs), "PREPARING MINECRAFT <v>", the version - or the percent
// while the stage carries a count - the 420 progress track (a sliding segment while there is no count), the stage
// line and Cancel. A failed install keeps the screen up with the reason, Retry and Close. Bottom left: the family
// label and whose launch it is. It only shows what LaunchController tells it; the buttons raise events.
public sealed class LaunchCover : Panel
{
    private readonly Image _logo = Ui.Logo(Sizes.LogoHero);
    private readonly TextBlock _eyebrow = Ui.Text("", TypeScale.Label, FontWeight.SemiBold, Palette.Brush("OnPhoto"), TypeScale.CoverTracking);
    private readonly TextBlock _big = Ui.Text("", TypeScale.Display, FontWeight.ExtraBold, Palette.Brush("OnPhoto"), -0.03);
    private readonly TextBlock _percent = Ui.Text("%", TypeScale.Title, FontWeight.Bold, Palette.Brush("OnPhoto"));
    private readonly Rail _track = new() { Width = Sizes.CoverTrack, Value = double.NaN };
    private readonly TextBlock _stage = Ui.Ellipsis(Ui.Caption("", Palette.Text), Sizes.CoverStage);
    private readonly TextBlock _message = Ui.Body("", Palette.Text);
    private readonly Pressable _retry = Ui.Button("Retry", Look.Primary, height: Sizes.ControlMd);
    private readonly Pressable _cancel = Ui.Button("Cancel", Look.Secondary, height: Sizes.ControlMd);
    private readonly TextBlock _cancelLabel;
    private readonly TextBlock _family = Ui.Text("", TypeScale.Label, FontWeight.SemiBold, Palette.Brush("OnPhoto"), TypeScale.CoverTracking);
    private readonly TextBlock _detail = Ui.Ellipsis(Ui.Caption(""), 480);
    private readonly TextBlock _caption = Ui.Caption("", Palette.TextDim);
    private readonly StackPanel _column;
    private readonly Tween _columnMotion, _stageMotion;
    private readonly Control _corner;
    private DispatcherTimer? _breath;
    private string _version = "";
    private int _stageRevision;
    private bool _updateMode;

    public event Action? CancelRequested;
    public event Action? RetryRequested;
    public event Action? CloseRequested;
    /// <summary>Cancel on the self-update's "waiting for Minecraft to close": go back, the update stays ready.</summary>
    public event Action? UpdateWaitCancelRequested;

    /// <summary>The cover shows the launcher's own update (ShowUpdate), not a launch.</summary>
    public bool ShowsUpdate => _updateMode;

    /// <summary>The cover shows a failed install (Retry / Close) rather than a launch in progress.</summary>
    public bool ShowsFailure { get; private set; }

    public LaunchCover()
    {
        Background = Palette.LaunchScrim;

        _logo.HorizontalAlignment = HorizontalAlignment.Center;
        _logo.Margin = new Thickness(0, 0, 0, Space.S6);
        _eyebrow.HorizontalAlignment = HorizontalAlignment.Center;
        _eyebrow.TextAlignment = TextAlignment.Center;
        _eyebrow.TextWrapping = TextWrapping.Wrap;
        _percent.Margin = new Thickness(6, 0, 0, 0);
        _percent.VerticalAlignment = VerticalAlignment.Bottom;
        _big.VerticalAlignment = VerticalAlignment.Bottom;
        // The "%" sits on the number's baseline: both bottom-aligned, the smaller one lifted by its descent difference.
        _percent.Padding = new Thickness(0, 0, 0, 5);
        var bigRow = Ui.Row(0, _big, _percent);
        bigRow.HorizontalAlignment = HorizontalAlignment.Center;
        bigRow.Margin = new Thickness(0, Space.S1, 0, Space.S3);
        _track.HorizontalAlignment = HorizontalAlignment.Center;
        _track.Margin = new Thickness(0, 0, 0, Space.S4);
        _stage.HorizontalAlignment = HorizontalAlignment.Center;
        _stage.TextAlignment = TextAlignment.Center;
        _message.TextAlignment = TextAlignment.Center;
        _message.HorizontalAlignment = HorizontalAlignment.Center;
        _message.MaxWidth = Sizes.CoverStage;
        _message.IsVisible = false;
        _caption.HorizontalAlignment = HorizontalAlignment.Center;
        _caption.TextAlignment = TextAlignment.Center;
        _caption.Margin = new Thickness(0, Space.S2, 0, 0);
        _caption.IsVisible = false;

        _retry.MinWidth = 96;
        _cancel.MinWidth = 96;
        _cancelLabel = (TextBlock)_cancel.Child!;
        _retry.IsVisible = false;
        var buttons = Ui.Row(Space.S3, _retry, _cancel);
        buttons.HorizontalAlignment = HorizontalAlignment.Center;
        buttons.Margin = new Thickness(0, 28, 0, 0);
        _retry.Click += (_, _) => RetryRequested?.Invoke();
        _cancel.Click += (_, _) => OnCancel();

        var stack = Ui.Column(0, _logo, _eyebrow, bigRow, _track, _stage, _caption, _message, buttons);
        stack.Width = Sizes.CoverStage;
        stack.HorizontalAlignment = HorizontalAlignment.Center;
        stack.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(stack);
        _column = stack;
        // The stage column rises 12 px and fades in over Motion.Scene, cubic ease-out, each time a scene starts (WPF
        // LaunchLoadingOverlay.ShowScene); a new stage line fades out over Motion.Exit and in over Motion.Base.
        _columnMotion = new Tween(_column, transform: true, opacityEasing: Motion.CubicOut, transformEasing: Motion.CubicOut);
        _stageMotion = new Tween(_stage);

        _detail.Margin = new Thickness(0, 6, 0, 0);
        var corner = Ui.Column(0, _family, _detail);
        corner.HorizontalAlignment = HorizontalAlignment.Left;
        corner.VerticalAlignment = VerticalAlignment.Bottom;
        corner.Margin = new Thickness(Space.S8, 0, 0, Space.S8);
        Children.Add(corner);
        _corner = corner;

        AttachedToVisualTree += (_, _) => StartBreath();
        DetachedFromVisualTree += (_, _) => { _breath?.Stop(); _breath = null; };
    }

    // The logo breathes (1 -> 0.45 -> 1 over 2.4 s) while the cover is up; still in render runs (motion off).
    private void StartBreath()
    {
        if (Motion.Off || _breath != null) return;
        var born = DateTime.UtcNow;
        _breath = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(33) };
        _breath.Tick += (_, _) =>
        {
            var t = (DateTime.UtcNow - born).TotalSeconds % 2.4 / 1.2; // 0..2
            var k = t <= 1 ? t : 2 - t;
            var eased = k * k * (3 - 2 * k);
            _logo.Opacity = 1 - 0.55 * eased;
        };
        _breath.Start();
    }

    /// <summary>A launch begins: "Preparing", the track sliding, Cancel. caption = whose launch (bottom left).</summary>
    public void Show(string version, string caption)
    {
        _version = version;
        _updateMode = false;
        _corner.IsVisible = true;
        _caption.IsVisible = false;
        _cancel.IsVisible = true;
        ShowsFailure = false;
        _eyebrow.Foreground = Palette.Brush("OnPhoto");
        _eyebrow.Text = ("Preparing Minecraft " + version).ToUpperInvariant();
        _big.Text = version;
        _percent.IsVisible = false;
        _track.IsVisible = true;
        _track.Value = double.NaN;
        _stage.IsVisible = true;
        _stage.Text = "Preparing";
        _message.IsVisible = false;
        _retry.IsVisible = false;
        _cancel.IsEnabled = true;
        _cancelLabel.Text = "Cancel";
        ToolTip.SetTip(_cancel, null);
        _family.Text = FamilyTheme.Of(version).Label.ToUpperInvariant();
        _detail.Text = caption;
        _detail.IsVisible = caption.Length > 0;
        RiseIn();
    }

    // The scene's entrance: the column from 12 px below and transparent to its place (WPF ShowScene).
    private void RiseIn()
    {
        SetStageImmediately(_stage.Text ?? "");
        if (Motion.Off) return;
        _columnMotion.Jump(opacity: 0, transform: "translateY(12px)");
        if (this.IsAttachedToVisualTree()) { Go(); return; }
        AttachedToVisualTree += Once;
        void Once(object? s, VisualTreeAttachmentEventArgs e) { AttachedToVisualTree -= Once; Go(); }
        void Go() => _columnMotion.To(Motion.Scene, opacity: 1, transform: "none");
    }

    /// <summary>
    /// The launcher's own update on the same scene (WPF LaunchLoadingOverlay.ShowUpdate): "UPDATING ORIGIN LAUNCHER TO
    /// x", a real percentage fill fed by <see cref="ReportUpdate"/>, "Getting the newest version" under the stage line, no
    /// Cancel while it downloads (updates are mandatory) and no corner caption. Cancel appears only while the swap waits
    /// for Minecraft to close, which can last hours.
    /// </summary>
    public void ShowUpdate(string newVersion)
    {
        _version = newVersion;
        _updateMode = true;
        ShowsFailure = false;
        _eyebrow.Foreground = Palette.Brush("OnPhoto");
        _eyebrow.Text = ("Updating Origin Launcher to " + newVersion).ToUpperInvariant();
        _track.IsVisible = true;
        _stage.IsVisible = true;
        _stage.Text = "Preparing";
        _caption.Text = "Getting the newest version";
        _caption.IsVisible = true;
        _message.IsVisible = false;
        _retry.IsVisible = false;
        _cancel.IsVisible = false;
        _cancel.IsEnabled = true;
        _cancelLabel.Text = "Cancel";
        ToolTip.SetTip(_cancel, null);
        _corner.IsVisible = false;
        ShowCount(0);
        RiseIn();
    }

    /// <summary>Moves the update fill and stage line. Byte ticks inside one stage swap the line in place (they arrive
    /// ~10 a second; a fade each time would leave it invisible); a new stage fades (WPF ReportUpdate).</summary>
    public void ReportUpdate(App.Core.Updates.UpdateService.UpdateProgress progress)
    {
        var downloading = progress.Stage == App.Core.Updates.UpdateService.UpdateStage.Downloading;
        if (downloading && progress.ReceivedBytes > 0) SetStageImmediately(progress.Describe());
        else FadeStage(progress.Describe());
        if ((downloading ? progress.Fraction : 1.0) is { } f) ShowCount(f);

        // Waiting for a game to close: never a locked screen. Cancel goes back to Home and the update stays ready.
        var waiting = progress.Stage == App.Core.Updates.UpdateService.UpdateStage.WaitingForGame;
        _cancel.IsVisible = waiting;
        _cancel.IsEnabled = waiting;
        ToolTip.SetTip(_cancel, waiting ? "Go back - the update stays ready" : null);
        _caption.Text = waiting ? "Origin updates the moment Minecraft closes" : "Getting the newest version";
    }

    private void ShowCount(double f)
    {
        _track.Value = Math.Clamp(f, 0, 1);
        _big.Text = Fmt.N(Math.Floor(Math.Clamp(f, 0, 1) * 100));
        _percent.IsVisible = true;
    }

    // A new stage: the line fades out over Motion.Exit, takes the new words and fades in over Motion.Base. A newer stage
    // during the fade wins (the revision), so the line never ends on stale words or stays faded out.
    private void FadeStage(string stage)
    {
        if (_stage.Text == stage) return;
        if (Motion.Off || !this.IsAttachedToVisualTree()) { SetStageImmediately(stage); return; }
        var revision = ++_stageRevision;
        _stageMotion.To(Motion.Exit, opacity: 0);
        DispatcherTimer.RunOnce(() =>
        {
            if (revision != _stageRevision) return;
            _stage.Text = stage;
            _stageMotion.To(Motion.Base, opacity: 1);
        }, Motion.Exit);
    }

    private void SetStageImmediately(string stage)
    {
        ++_stageRevision; // supersedes any fade still in flight
        _stageMotion.Jump(opacity: 1);
        _stage.Text = stage;
    }

    /// <summary>One install/launch step. A step with a count ("Downloading libraries (240/600)") fills the track and
    /// shows the percent; one without slides the track and shows the version.</summary>
    public void ReportStage(string stage)
    {
        if (ShowsFailure || _updateMode) return;
        var p = StageProgress.Parse(stage);
        // A count ticking inside one stage ("Downloading libraries (240/600)") swaps in place; only a new stage fades.
        if (p.Fraction != null && StageProgress.Parse(_stage.Text ?? "").Label == p.Label) SetStageImmediately(stage);
        else FadeStage(stage);
        if (p.Fraction is { } f)
        {
            _track.Value = f;
            _big.Text = Fmt.N(Math.Floor(Math.Clamp(f, 0, 1) * 100));
            _percent.IsVisible = true;
        }
        else
        {
            _track.Value = double.NaN;
            _big.Text = _version;
            _percent.IsVisible = false;
        }
    }

    /// <summary>The game process is up: Cancel now stops Minecraft starting.</summary>
    public void EnterGameStart() => ToolTip.SetTip(_cancel, "Stop Minecraft starting");

    /// <summary>The install did not finish: why, in plain words, with Retry and Close. Files are left as they were.</summary>
    public void ShowFailure(PlayFailure failure)
    {
        ShowsFailure = true;
        _eyebrow.Text = failure.Title.ToUpperInvariant();
        _eyebrow.Foreground = Palette.DangerText;
        _big.Text = _version;
        _percent.IsVisible = false;
        _track.IsVisible = false;
        _stage.IsVisible = false;
        _stageRevision++;
        _message.Text = failure.Message;
        _message.IsVisible = true;
        _retry.IsVisible = true;
        _cancel.IsEnabled = true;
        _cancelLabel.Text = "Close";
        ToolTip.SetTip(_cancel, null);
        Dispatcher.UIThread.Post(() => _retry.Focus(), DispatcherPriority.Input);
    }

    private void OnCancel()
    {
        if (_updateMode)
        {
            _cancel.IsEnabled = false;
            UpdateWaitCancelRequested?.Invoke();
            return;
        }
        if (ShowsFailure)
        {
            CloseRequested?.Invoke();
            return;
        }
        // Instant feedback: the button greys and the line says so; the launch unwinds and lowers the cover itself.
        _cancel.IsEnabled = false;
        _stage.Text = "Cancelling";
        CancelRequested?.Invoke();
    }

    // ---- Render runs ----

    public Pressable CancelButton => _cancel;
    public Pressable RetryButton => _retry;
}
