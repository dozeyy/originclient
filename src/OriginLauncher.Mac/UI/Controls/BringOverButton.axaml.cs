using System.Globalization;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF UI/Controls/BringOverButton.xaml.cs: Home's one bring-over button, drawing Core's BringOverModel. It
/// only draws and reports presses: MainWindow.Import.cs runs the transfer (HomeImportRunner), writes the answer
/// (SettingsStore.Update) and opens the Import page.
/// </summary>
public partial class BringOverButton : UserControl
{
    /// <summary>A transfer should start: one launcher's setups, or every launcher's (null). The button already shows it running.</summary>
    public event EventHandler<SourceKind?>? RunRequested;

    /// <summary>Stop was pressed while a transfer runs.</summary>
    public event EventHandler? StopRequested;

    /// <summary>The player answered for good (No thanks, or a transfer came over): the window records it.</summary>
    public event EventHandler? Answered;

    /// <summary>"Choose what comes over": the full Import page.</summary>
    public event EventHandler? MoreRequested;

    // The rise in: an 8 px drop from above (timed by Motion.Enter in the axaml's transitions).
    private static readonly ITransform Raised = TransformOperations.Parse("translateY(-8px)");
    private static readonly ITransform Rested = TransformOperations.Parse("translateY(0px)");
    private static readonly ITransform Turned = TransformOperations.Parse("rotate(180deg)");

    public BringOverModel Model { get; } = new();

    public BringOverButton()
    {
        InitializeComponent();
        AllText.Text = ImportPrompt.AllLabel;
        MoreText.Text = ImportPrompt.MoreLabel;
    }

    public bool IsRunning => Model.Stage == BringOverStage.Running;

    /// <summary>The scan's qualifying setups (ImportPrompt.Qualifying): the button appears, or none hides it.</summary>
    public void Offer(IReadOnlyCollection<DetectedInstance> qualifying)
    {
        var wasVisible = IsVisible;
        Model.Offer(qualifying);
        BuildRows();
        Render();
        if (!wasVisible && IsVisible) Enter();
    }

    /// <summary>The sentence from the wiring's refusal check, or null when Import can run: an offer hides meanwhile.</summary>
    public void SetRefusal(string? sentence)
    {
        Model.SetRefused(sentence != null);
        Render();
    }

    /// <summary>The Import page opened from anywhere: the offer has done its job.</summary>
    public void HideOffer()
    {
        Model.HideOffer();
        Render();
    }

    public void Report(HomeImportProgress progress)
    {
        Model.Report(progress);
        Render();
    }

    public void Finish(HomeImportOutcome outcome)
    {
        if (Model.Finish(outcome)) Answered?.Invoke(this, EventArgs.Empty);
        Render();
    }

    // ---- presses ----

    private void Main_Click(object? sender, RoutedEventArgs e)
    {
        switch (Model.Press())
        {
            case BringOverPress.RunOne:
                StartRun(Model.OneSource);
                break;
            case BringOverPress.Choose:
                Render();
                break;
        }
    }

    private void All_Click(object? sender, RoutedEventArgs e) => StartRun(null);

    private void More_Click(object? sender, RoutedEventArgs e) => MoreRequested?.Invoke(this, EventArgs.Empty);

    private void Close_Click(object? sender, RoutedEventArgs e)
    {
        if (Model.Dismiss()) Answered?.Invoke(this, EventArgs.Empty);
        Render();
    }

    private void Action_Click(object? sender, RoutedEventArgs e)
    {
        if (Model.Stage == BringOverStage.Running)
        {
            ActionButton.IsEnabled = false; // one Stop is enough: the engine is putting everything back
            StopRequested?.Invoke(this, EventArgs.Empty);
        }
        else if (Model.Retry(out var source))
        {
            Render();
            RunRequested?.Invoke(this, source);
        }
    }

    private void StartRun(SourceKind? source)
    {
        if (!Model.Start(source)) return;
        Render();
        RunRequested?.Invoke(this, source);
    }

    // ---- drawing ----

    private void Render()
    {
        var stage = Model.Stage;
        IsVisible = Model.Visible;
        LineText.Text = Model.Line;
        AutomationProperties.SetName(MainButton, Model.Line);
        DetailText.Text = Model.Detail ?? "";
        DetailText.IsVisible = Model.Detail != null;

        var offering = stage is BringOverStage.Offer or BringOverStage.Choosing;
        MainButton.IsHitTestVisible = MainButton.Focusable = offering;
        Chevron.IsVisible = offering && Model.OneSource == null;
        Chevron.RenderTransform = stage == BringOverStage.Choosing ? Turned : null;
        ChoicePanel.IsVisible = stage == BringOverStage.Choosing;

        // The one colour: the glyph. Accent while it offers or runs, success when it came over, warning when it didn't.
        (LeadIcon.Data, LeadIcon.Stroke) = stage switch
        {
            BringOverStage.Done => (Res.Geo("Icon.Check"), Res.Brush("Brush.Success")),
            BringOverStage.Failed => (Res.Geo("Icon.Alert"), Res.Brush("Brush.Warning")),
            _ => (Res.Geo("Icon.ImportIn"), Res.Brush("Brush.Accent")),
        };

        ActionButton.IsVisible = stage is BringOverStage.Running or BringOverStage.Failed;
        ActionButton.Content = stage == BringOverStage.Running ? "Stop" : "Try again";
        if (stage != BringOverStage.Running) ActionButton.IsEnabled = true;
        CloseButton.IsVisible = stage != BringOverStage.Running;
        var close = offering ? ImportPrompt.NoThanksLabel : "Close";
        ToolTip.SetTip(CloseButton, close);
        AutomationProperties.SetName(CloseButton, close);

        Rail.IsVisible = Model.Fraction != null;
        RailFill.RenderTransform = TransformOperations.Parse(
            "scaleX(" + (Model.Fraction ?? 0).ToString("0.###", CultureInfo.InvariantCulture) + ")");
    }

    // Opacity + translate only, and it never blocks input: the buttons work from the first frame.
    private void Enter()
    {
        if (Motion.Off) return;
        Shell.Opacity = 0;
        Shell.RenderTransform = Raised;
        Dispatcher.UIThread.Post(() =>
        {
            Shell.Opacity = 1;
            Shell.RenderTransform = Rested;
        }, DispatcherPriority.Render);
    }

    // One row per launcher, most setups first: its real logo (or initial) in a round well, its name, how many setups.
    private void BuildRows()
    {
        SourceRows.Children.Clear();
        foreach (var source in Model.Sources)
        {
            var title = ImportFlowModel.LauncherTitle(source.Kind);
            var logo = LauncherLogos.For(source.Kind);
            Control mark = logo != null
                ? new Image { Source = logo, Width = 18, Height = 18 }
                : new TextBlock
                {
                    Text = ImportLogic.Initial(title),
                    FontSize = Res.D("FontSize.Caption"),
                    FontWeight = FontWeight.Bold,
                    Foreground = Res.Brush("Brush.Text"),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                };
            if (logo != null) RenderOptions.SetBitmapInterpolationMode(mark, Avalonia.Media.Imaging.BitmapInterpolationMode.HighQuality);
            var well = new Border
            {
                Width = Res.D("Size.ControlSm"),
                Height = Res.D("Size.ControlSm"),
                Margin = new Thickness(0, 0, 10, 0),
                Background = Res.Brush("Brush.Bg"),
                Child = mark,
            };
            Capsule.SetOn(well, true); // a square well: a circle

            var row = Res.Columns("Auto", "*", "Auto");
            row.Children.Add(well);
            var name = new TextBlock { Text = source.Name, Theme = Res.Theme("Text.BodyStrong"), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(name, 1);
            row.Children.Add(name);
            var count = new TextBlock
            {
                Text = ImportLogic.Plural(source.Setups, "setup", "setups"),
                Theme = Res.Theme("Text.Caption"),
                Foreground = Res.Brush("Brush.TextMuted"),
                Margin = new Thickness(16, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(count, 2);
            row.Children.Add(count);

            var kind = source.Kind;
            var button = new Button
            {
                Theme = Res.Theme("Button.Chrome"),
                CornerRadius = Res.Radius("Radius.Sm"),
                Foreground = Res.Brush("Brush.Text"),
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Stretch,
                MinHeight = Res.D("Size.ControlXl"),
                Padding = new Thickness(10, 0, 12, 0),
                Content = row,
            };
            AutomationProperties.SetName(button, ImportPrompt.OneLabel(source.Name));
            button.Click += (_, _) => StartRun(kind);
            SourceRows.Children.Add(button);
        }
    }

    // ---- render shots: the states without a real transfer ----

    /// <summary>A press that only ever opens the choice (one launcher's press would start a real transfer: never in a render).</summary>
    internal void TourPress()
    {
        if (Model.OneSource == null && Model.Press() == BringOverPress.Choose) Render();
    }

    internal void TourStart(SourceKind? source)
    {
        Model.Start(source);
        Render();
    }

    internal void TourHide()
    {
        Model.Dismiss();
        Render();
    }
}
