using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
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
    private DispatcherTimer? _breath;
    private string _version = "";

    public event Action? CancelRequested;
    public event Action? RetryRequested;
    public event Action? CloseRequested;

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

        _retry.MinWidth = 96;
        _cancel.MinWidth = 96;
        _cancelLabel = (TextBlock)_cancel.Child!;
        _retry.IsVisible = false;
        var buttons = Ui.Row(Space.S3, _retry, _cancel);
        buttons.HorizontalAlignment = HorizontalAlignment.Center;
        buttons.Margin = new Thickness(0, 28, 0, 0);
        _retry.Click += (_, _) => RetryRequested?.Invoke();
        _cancel.Click += (_, _) => OnCancel();

        var stack = Ui.Column(0, _logo, _eyebrow, bigRow, _track, _stage, _message, buttons);
        stack.Width = Sizes.CoverStage;
        stack.HorizontalAlignment = HorizontalAlignment.Center;
        stack.VerticalAlignment = VerticalAlignment.Center;
        Children.Add(stack);

        _detail.Margin = new Thickness(0, 6, 0, 0);
        var corner = Ui.Column(0, _family, _detail);
        corner.HorizontalAlignment = HorizontalAlignment.Left;
        corner.VerticalAlignment = VerticalAlignment.Bottom;
        corner.Margin = new Thickness(Space.S8, 0, 0, Space.S8);
        Children.Add(corner);

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
    }

    /// <summary>One install/launch step. A step with a count ("Downloading libraries (240/600)") fills the track and
    /// shows the percent; one without slides the track and shows the version.</summary>
    public void ReportStage(string stage)
    {
        if (ShowsFailure) return;
        var p = StageProgress.Parse(stage);
        _stage.Text = stage;
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
