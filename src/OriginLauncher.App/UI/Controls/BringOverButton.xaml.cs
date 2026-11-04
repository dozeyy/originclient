using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;

namespace OriginLauncher.App.UI.Controls;

/// <summary>
/// Home's one bring-over button (the Windows face of Core's BringOverModel; the Mac and Linux Homes draw the same model).
/// It only draws and reports presses: MainWindow runs the transfer (HomeImportRunner), writes the answer
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
        var wasVisible = Visibility == Visibility.Visible;
        Model.Offer(qualifying);
        BuildRows();
        Render();
        if (!wasVisible && Visibility == Visibility.Visible) Enter();
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

    private void Main_Click(object sender, RoutedEventArgs e)
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

    private void All_Click(object sender, RoutedEventArgs e) => StartRun(null);

    private void More_Click(object sender, RoutedEventArgs e) => MoreRequested?.Invoke(this, EventArgs.Empty);

    private void Close_Click(object sender, RoutedEventArgs e)
    {
        if (Model.Dismiss()) Answered?.Invoke(this, EventArgs.Empty);
        Render();
    }

    private void Action_Click(object sender, RoutedEventArgs e)
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
        Visibility = Model.Visible ? Visibility.Visible : Visibility.Collapsed;
        LineText.Text = Model.Line;
        System.Windows.Automation.AutomationProperties.SetName(MainButton, Model.Line);
        DetailText.Text = Model.Detail ?? "";
        DetailText.Visibility = Model.Detail != null ? Visibility.Visible : Visibility.Collapsed;

        var offering = stage is BringOverStage.Offer or BringOverStage.Choosing;
        MainButton.IsHitTestVisible = MainButton.Focusable = offering;
        Chevron.Visibility = offering && Model.OneSource == null ? Visibility.Visible : Visibility.Collapsed;
        ChevronTurn.Angle = stage == BringOverStage.Choosing ? 180 : 0;
        ChoicePanel.Visibility = stage == BringOverStage.Choosing ? Visibility.Visible : Visibility.Collapsed;

        // The one colour: the glyph. Accent while it offers or runs, success when it came over, warning when it didn't.
        (LeadIcon.Data, LeadIcon.Stroke) = stage switch
        {
            BringOverStage.Done => (Res<Geometry>("Icon.Check"), Res<Brush>("Brush.Success")),
            BringOverStage.Failed => (Res<Geometry>("Icon.Alert"), Res<Brush>("Brush.Warning")),
            _ => (Res<Geometry>("Icon.ImportIn"), Res<Brush>("Brush.Accent")),
        };

        ActionButton.Visibility = stage is BringOverStage.Running or BringOverStage.Failed ? Visibility.Visible : Visibility.Collapsed;
        ActionButton.Content = stage == BringOverStage.Running ? "Stop" : "Try again";
        if (stage != BringOverStage.Running) ActionButton.IsEnabled = true;
        CloseButton.Visibility = stage == BringOverStage.Running ? Visibility.Collapsed : Visibility.Visible;
        CloseButton.ToolTip = stage is BringOverStage.Offer or BringOverStage.Choosing ? ImportPrompt.NoThanksLabel : "Close";
        System.Windows.Automation.AutomationProperties.SetName(CloseButton, CloseButton.ToolTip as string);

        Rail.Visibility = Model.Fraction != null ? Visibility.Visible : Visibility.Collapsed;
        RailFill.ScaleX = Model.Fraction ?? 0;
    }

    // Rise in (opacity + translate only, Motion.Enter 220 ms and an 8 px drop from above). Never blocks input.
    private void Enter()
    {
        if (Motion.Off) return;
        Shell.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 220));
        Lift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(-8, 0, 220, Motion.Out));
    }

    // One row per launcher, most setups first: its real logo (or initial) in a round well, its name, how many setups.
    private void BuildRows()
    {
        SourceRows.Children.Clear();
        foreach (var source in Model.Sources)
        {
            var title = ImportFlowModel.LauncherTitle(source.Kind);
            var logo = LauncherLogos.For(source.Kind);
            var well = new Border
            {
                Width = Res<double>("Size.ControlSm"),
                Height = Res<double>("Size.ControlSm"),
                Margin = new Thickness(0, 0, 10, 0),
                Background = Res<Brush>("Brush.Bg"),
                Child = logo != null
                    ? new Image { Source = logo, Width = 18, Height = 18 }
                    : new TextBlock
                    {
                        Text = ImportLogic.Initial(title),
                        FontSize = Res<double>("FontSize.Caption"),
                        FontWeight = FontWeights.Bold,
                        Foreground = Res<Brush>("Brush.Text"),
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center,
                    },
            };
            Capsule.SetOn(well, true); // a square well: a circle
            if (logo != null) RenderOptions.SetBitmapScalingMode(well.Child, BitmapScalingMode.HighQuality);

            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(well);
            var name = new TextBlock { Text = source.Name, Style = Res<Style>("Text.BodyStrong"), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(name, 1);
            row.Children.Add(name);
            var count = new TextBlock
            {
                Text = ImportLogic.Plural(source.Setups, "setup", "setups"),
                Style = Res<Style>("Text.Caption"),
                Foreground = Res<Brush>("Brush.TextMuted"),
                Margin = new Thickness(16, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(count, 2);
            row.Children.Add(count);

            var kind = source.Kind;
            var button = new Button { Style = Res<Style>("BringOver.Row"), Content = row };
            System.Windows.Automation.AutomationProperties.SetName(button, ImportPrompt.OneLabel(source.Name));
            button.Click += (_, _) => StartRun(kind);
            SourceRows.Children.Add(button);
        }
    }

    private T Res<T>(string key) => (T)FindResource(key);

    // ---- render mode (CI): the states without a real transfer ----

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
