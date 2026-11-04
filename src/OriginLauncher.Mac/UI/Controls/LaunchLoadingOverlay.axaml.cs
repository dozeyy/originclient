using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.Core.Updates;
using OriginLauncher.App.UI;

namespace OriginLauncher.Mac.UI.Controls;

// The full-window progress screen (twin of the WPF UI/Controls/LaunchLoadingOverlay.xaml.cs): install + launch (Show)
// and the launcher's own self-update (ShowUpdate) read as the same Origin moment - the breathing logo, the title, the
// bar, the stage line. In a launch it stays up through Minecraft's start and lowers (Hide) once the game's own window
// is up showing its loading screen (Core/Launch/GameHandoff decides when). The stage lines and their counts are the
// shared Core/Launch/StageProgress; the failure words are Core/Launch/PlayFailure.
public sealed partial class LaunchLoadingOverlay : UserControl
{
    private long _sceneRevision;
    private bool _launchMode;
    private bool _failed;
    private string _bigFallback = "";

    public LaunchLoadingOverlay()
    {
        InitializeComponent();
        RetryButton.Click += (_, _) =>
        {
            // One shot, like Cancel: the page starts a fresh launch, whose Show() puts the progress look back.
            RetryButton.IsEnabled = false;
            RetryRequested?.Invoke();
        };
        RosettaButton.Click += (_, _) =>
        {
            RosettaButton.IsEnabled = false;
            RosettaRequested?.Invoke();
        };
        CancelButton.Click += (_, _) => PressCancel();
    }

    /// <summary>The player clicked Cancel: the launch flow cancels its in-flight preparation (or stops the starting game).</summary>
    public event Action? CancelRequested;

    /// <summary>Retry on the failure state.</summary>
    public event Action? RetryRequested;

    /// <summary>Close on the failure state; the page lowers the screen.</summary>
    public event Action? CloseRequested;

    /// <summary>Cancel while the self-update waits for Minecraft to close: the wait stops, the update stays ready
    /// (UpdateService.DownloadAndRestartAsync's stopWaiting). Twin of the WPF overlay's UpdateWaitCancelRequested.</summary>
    public event Action? UpdateWaitCancelRequested;

    /// <summary>"Install Rosetta" on the Rosetta failure (Apple Silicon, a version that runs through Rosetta).</summary>
    public event Action? RosettaRequested;

    public bool ShowsFailure => _failed;

    /// <summary>The stage line as shown (the Play-flow proof reads it).</summary>
    internal string StageLine => StageText.Text ?? "";

    /// <summary>The Play press. No loader name anywhere: the player never sees which loader a version runs on.</summary>
    public void Show(string version, string? caption = null)
    {
        _launchMode = true;
        ResetFailure();
        EyebrowText.Text = $"PREPARING MINECRAFT {version}";
        CancelButton.IsVisible = true;
        ToolTip.SetTip(CancelButton, null);
        LoaderCaptionText.IsVisible = false;
        CaptionFamily.Text = FamilyTheme.Of(version).Label;
        CaptionDetail.Text = caption ?? "";
        CaptionDetail.IsVisible = !string.IsNullOrEmpty(caption);
        CaptionPanel.IsVisible = true;
        ShowScene(version);
        ShowProgress(null);
    }

    /// <summary>Self-update: the same scene, no Cancel while it downloads (updates are mandatory), a fill fed by
    /// <see cref="ReportUpdate"/>. Cancel appears only while the swap waits for Minecraft to close.</summary>
    public void ShowUpdate(string newVersion)
    {
        _launchMode = false;
        ResetFailure();
        EyebrowText.Text = $"UPDATING ORIGIN LAUNCHER TO {newVersion}";
        CancelButton.IsVisible = false;
        LoaderCaptionText.Text = "Getting the newest version";
        LoaderCaptionText.IsVisible = true;
        CaptionPanel.IsVisible = false;
        ShowScene(newVersion);
        ShowProgress(0);
    }

    public void ReportUpdate(UpdateService.UpdateProgress progress)
    {
        StageText.Text = progress.Describe();
        var fraction = progress.Stage == UpdateService.UpdateStage.Downloading ? progress.Fraction : 1.0;
        if (fraction is { } f) ShowProgress(f);

        // Waiting for a game to close: never a locked screen. Cancel goes back to Home and the update stays ready.
        var waiting = progress.Stage == UpdateService.UpdateStage.WaitingForGame;
        CancelButton.IsVisible = waiting;
        CancelButton.IsEnabled = waiting;
        ToolTip.SetTip(CancelButton, waiting ? "Go back - the update stays ready" : null);
        LoaderCaptionText.Text = waiting ? "Origin updates the moment Minecraft closes" : "Getting the newest version";
    }

    /// <summary>The game process is up: Cancel now stops that starting game.</summary>
    public void EnterGameStart() => ToolTip.SetTip(CancelButton, "Stop Minecraft starting");

    /// <summary>
    /// Preparing the game failed (U3.3): the bar and the logo stop, the cause is said in one plain sentence and the player
    /// can Retry or Close (or, when this Mac needs Rosetta for the version, Install Rosetta). Files are left as they are.
    /// </summary>
    public void ShowFailure(PlayFailure failure)
    {
        _failed = true;
        Mark.Classes.Remove("breathing");
        Mark.Opacity = 1;
        ProgressSweep.Classes.Remove("sweeping");
        ProgressSweep.IsVisible = false;
        ProgressFill.IsVisible = false;
        ProgressTrack.IsVisible = false;
        ShowCount(false);

        EyebrowText.Text = failure.Title.ToUpperInvariant();
        EyebrowText.Foreground = Find("Brush.DangerText");
        StageText.Text = failure.Message;
        StageText.TextWrapping = TextWrapping.Wrap;
        StageText.TextTrimming = TextTrimming.None;
        StageText.TextAlignment = TextAlignment.Center;

        RosettaButton.IsVisible = failure.OffersRosettaInstall;
        RosettaButton.IsEnabled = true;
        RetryButton.IsVisible = !failure.OffersRosettaInstall;
        RetryButton.IsEnabled = true;
        CancelButton.Content = "Close";
        ToolTip.SetTip(CancelButton, null);
        CancelButton.IsEnabled = true;
        CancelButton.IsVisible = true;
        IsVisible = true;
        IsHitTestVisible = true;
        Opacity = 1;
        (failure.OffersRosettaInstall ? RosettaButton : RetryButton).Focus();
    }

    /// <summary>The Rosetta install finished without Rosetta: say why under the button and let the player try again.</summary>
    public void ShowRosettaResult(string detail)
    {
        StageText.Text = detail;
        RosettaButton.IsEnabled = true;
    }

    private void ResetFailure()
    {
        _failed = false;
        RetryButton.IsVisible = false;
        RosettaButton.IsVisible = false;
        CancelButton.Content = "Cancel";
        ProgressTrack.IsVisible = true;
        EyebrowText.Foreground = Find("Brush.OnPhoto");
        StageText.TextWrapping = TextWrapping.NoWrap;
        StageText.TextTrimming = TextTrimming.CharacterEllipsis;
        StageText.ClearValue(TextBlock.TextAlignmentProperty);
    }

    private void ShowScene(string title)
    {
        _bigFallback = title;
        VersionText.Text = title;
        StageText.Text = "Preparing";
        CancelButton.IsEnabled = true;
        ++_sceneRevision;
        IsVisible = true;
        IsHitTestVisible = true;
        Opacity = 1;
        ProgressSweep.IsVisible = false;
        ProgressSweep.Classes.Remove("sweeping");
        ProgressFill.IsVisible = false;
        ShowCount(false);
        SetFill(0, instant: true);
        // The logo is the loading indicator: it breathes (opacity only; never rotated or scaled). Off in render shots.
        Mark.Classes.Set("breathing", !Motion.Off);
    }

    private bool _counting;

    private void ShowCount(bool counting)
    {
        _counting = counting;
        PercentText.IsVisible = counting;
        if (!counting) VersionText.Text = _bigFallback;
    }

    // A real fraction (0..1) fills to it; null (nothing measurable) slides the block. Never a made-up percentage.
    private void ShowProgress(double? fraction)
    {
        if (fraction is not { } f)
        {
            ShowCount(false);
            if (ProgressSweep.IsVisible) return;
            ProgressFill.IsVisible = false;
            SetFill(0, instant: true);
            ProgressSweep.IsVisible = true;
            ProgressSweep.Classes.Set("sweeping", !Motion.Off);
            return;
        }
        f = Math.Clamp(f, 0, 1);
        ShowCount(true);
        if (ProgressSweep.IsVisible)
        {
            ProgressSweep.Classes.Remove("sweeping");
            ProgressSweep.IsVisible = false;
            SetFill(0, instant: true);
        }
        ProgressFill.IsVisible = true;
        SetFill(f, instant: false);
        if (_counting) VersionText.Text = ((int)Math.Floor(f * 100 + 1e-6)).ToString();
    }

    private void SetFill(double f, bool instant)
    {
        var b = TransformOperations.CreateBuilder(1);
        b.AppendScale(Math.Max(f, 0.0001), 1);
        if (!instant)
        {
            ProgressFill.RenderTransform = b.Build();
            return;
        }
        var transitions = ProgressFill.Transitions;
        ProgressFill.Transitions = null;
        ProgressFill.RenderTransform = b.Build();
        ProgressFill.Transitions = transitions;
    }

    /// <summary>Lowers the screen. Home is clickable from the moment it starts leaving.</summary>
    public void Hide()
    {
        ++_sceneRevision;
        IsHitTestVisible = false;
        Mark.Classes.Remove("breathing");
        ProgressSweep.Classes.Remove("sweeping");
        IsVisible = false;
    }

    public void ReportStage(string stage)
    {
        var progress = _launchMode ? StageProgress.Parse(stage) : new StageProgress(stage, null);
        if (_launchMode) ShowProgress(progress.Fraction);
        StageText.Text = stage;
    }

    /// <summary>Render shots and the Play-flow proof: presses Cancel as the player would.</summary>
    internal void PressCancel()
    {
        if (_failed)
        {
            CloseRequested?.Invoke();
            return;
        }
        if (!_launchMode)
        {
            // The self-update's wait for Minecraft to close (the only time update mode shows Cancel).
            CancelButton.IsEnabled = false;
            UpdateWaitCancelRequested?.Invoke();
            return;
        }
        // One shot: further clicks do nothing while the cancellation unwinds.
        CancelButton.IsEnabled = false;
        StageText.Text = "Cancelling";
        CancelRequested?.Invoke();
    }

    private IBrush? Find(string key) => this.ThemeValue<IBrush>(key);
}
