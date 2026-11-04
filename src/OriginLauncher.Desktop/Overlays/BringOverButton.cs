using System.Globalization;
using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// Home's one bring-over button (port of UI/Controls/BringOverButton.xaml; state: Core/Import/Launcher/BringOverModel).
// Top centre, 16 under the bar, a neutral 40 tall capsule whose one colour is its small leading glyph, so it never
// competes with Play. One launcher: a press brings it over at once. Several: a press opens the choice under it. While it
// runs: the line counts up and a 3 px rail runs inside the bottom edge, with Stop. Then one plain line and a close.
// It only draws and reports presses: ImportView runs the transfer, records the answer and opens the Import page.
// No entrance animation: it is there, and works, from the first frame (opening any screen must feel instant).
public sealed class BringOverButton : StackPanel
{
    /// <summary>A transfer should start: one launcher's setups, or every launcher's (null). The button already shows it running.</summary>
    public event Action<SourceKind?>? RunRequested;

    /// <summary>Stop was pressed while a transfer runs.</summary>
    public event Action? StopRequested;

    /// <summary>The player answered for good (No thanks, or a transfer came over): the owner records it.</summary>
    public event Action? Answered;

    /// <summary>"Choose what comes over": the full Import page.</summary>
    public event Action? MoreRequested;

    public BringOverModel Model { get; } = new();

    private readonly Pressable _main;
    private readonly IconView _lead = Ui.Icon(Icons.ImportIn, Sizes.IconSm, Palette.Accent);
    private readonly TextBlock _line = Ui.Wrap(Ui.BodyStrong(""));
    private readonly TextBlock _detail = Ui.Wrap(Ui.Caption("", Palette.TextDim));
    private readonly IconView _chevron = Ui.Icon(Icons.ChevronDown, Sizes.IconSm, Palette.TextDim);
    private readonly Pressable _action;
    private readonly TextBlock _actionText = Ui.Text("Stop", TypeScale.Body, FontWeight.SemiBold);
    private readonly Pressable _close;
    private readonly Border _rail;
    private readonly Border _railFill;
    private readonly Border _choice;
    private readonly StackPanel _rows = new();

    private static readonly ITransform Turned = TransformOperations.Parse("rotate(180deg)");

    public BringOverButton()
    {
        IsVisible = false;
        HorizontalAlignment = HorizontalAlignment.Center;
        VerticalAlignment = VerticalAlignment.Top;
        Margin = new Thickness(Space.S8, Space.S4, Space.S8, 0);

        _line.MaxWidth = _detail.MaxWidth = 440;
        _detail.Margin = new Thickness(0, 2, 0, 0);
        _lead.Margin = new Thickness(0, 0, 10, 0);
        _lead.VerticalAlignment = VerticalAlignment.Center;
        _chevron.Margin = new Thickness(10, 0, 0, 0);
        _chevron.VerticalAlignment = VerticalAlignment.Center;
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 6) };
        words.Children.Add(_line);
        words.Children.Add(_detail);
        var face = new StackPanel { Orientation = Orientation.Horizontal };
        face.Children.Add(_lead);
        face.Children.Add(words);
        face.Children.Add(_chevron);
        _main = new Pressable(Look.Row, face)
        {
            MinHeight = Sizes.ControlMd, Padding = new Thickness(12, 0, 14, 0), CornerRadius = new CornerRadius(Radius.CapsuleMd),
        };
        _main.Click += (_, _) => Press();

        // Stop while it runs, Try again after a failed run.
        _action = new Pressable(Look.Ghost, _actionText)
        {
            Height = Sizes.ControlMd, Padding = new Thickness(12, 0), CornerRadius = new CornerRadius(Radius.CapsuleMd),
            Margin = new Thickness(4, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
        }.Center();
        _action.Click += (_, _) => ActionPressed();

        _close = Ui.IconButton(Icons.Close, ImportPrompt.NoThanksLabel);
        _close.CornerRadius = new CornerRadius(Radius.CapsuleMd);
        _close.Margin = new Thickness(2, 0, 0, 0);
        _close.Click += (_, _) => Close();

        // The progress rail (DESIGN-SYSTEM 11.11) inside the bottom edge; only once there is a real fraction.
        _railFill = new Border
        {
            Background = Palette.Accent, CornerRadius = new CornerRadius(2),
            RenderTransformOrigin = new RelativePoint(0, 0.5, RelativeUnit.Relative),
        };
        _rail = new Border
        {
            Height = Sizes.Progress, Margin = new Thickness(16, 0, 16, 1), VerticalAlignment = VerticalAlignment.Bottom,
            Background = Palette.ProgressTrack, CornerRadius = new CornerRadius(2), IsHitTestVisible = false, IsVisible = false,
            Child = _railFill,
        };

        var inside = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto") };
        inside.Children.Add(_main.At(0));
        inside.Children.Add(_action.At(1));
        inside.Children.Add(_close.At(2));
        Grid.SetColumnSpan(_rail, 3);
        inside.Children.Add(_rail);
        var shell = Ui.Capsule(new Border
        {
            MinHeight = Sizes.ControlXl, Padding = new Thickness(3), HorizontalAlignment = HorizontalAlignment.Center,
            Background = Palette.Card, BorderBrush = Palette.LineStrong, BorderThickness = new Thickness(1), Child = inside,
        });
        Children.Add(shell);

        // Several launchers: one row per launcher (logo, name, how many setups), All of them, then the full Import page.
        var all = Row(ImportPartsWell(), ImportPrompt.AllLabel, null);
        all.Click += (_, _) => StartRun(null);
        var moreFace = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        moreFace.Children.Add(Ui.Body(ImportPrompt.MoreLabel, Palette.TextDim).Align(HorizontalAlignment.Left).At(0));
        var arrow = Ui.Icon(Icons.ChevronDown, Sizes.IconSm, Palette.TextDim).At(1);
        arrow.RenderTransform = TransformOperations.Parse("rotate(-90deg)"); // the chevron points down in the set: turned, "goes on to a page"
        arrow.VerticalAlignment = VerticalAlignment.Center;
        moreFace.Children.Add(arrow);
        var more = new Pressable(Look.Row, moreFace)
        {
            MinHeight = Sizes.ControlXl, Padding = new Thickness(10, 0, 12, 0), CornerRadius = new CornerRadius(Radius.Sm),
        };
        more.Click += (_, _) => MoreRequested?.Invoke();
        var list = new StackPanel();
        list.Children.Add(_rows);
        list.Children.Add(all);
        list.Children.Add(Ui.Divider().Margin(8, 6, 8, 6));
        list.Children.Add(more);
        _choice = new Border
        {
            IsVisible = false, HorizontalAlignment = HorizontalAlignment.Center, MinWidth = 320, Margin = new Thickness(0, Space.S2, 0, 0),
            Padding = new Thickness(6), Background = Palette.Panel, BorderBrush = Palette.LineStrong, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(Radius.Lg), Child = list,
        };
        Children.Add(_choice);
    }

    public bool IsRunning => Model.Stage == BringOverStage.Running;

    /// <summary>The scan's qualifying setups (ImportPrompt.Qualifying): the button appears, or none hides it.</summary>
    public void Offer(IReadOnlyCollection<DetectedInstance> qualifying)
    {
        Model.Offer(qualifying);
        BuildRows();
        Render();
    }

    /// <summary>The sentence from the refusal check, or null when Import can run: an offer hides meanwhile.</summary>
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
        if (Model.Finish(outcome)) Answered?.Invoke();
        Render();
    }

    // ---- presses ----

    private void Press()
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

    private void Close()
    {
        if (Model.Dismiss()) Answered?.Invoke();
        Render();
    }

    private void ActionPressed()
    {
        if (Model.Stage == BringOverStage.Running)
        {
            _action.IsEnabled = false; // one Stop is enough: the engine is putting everything back
            StopRequested?.Invoke();
        }
        else if (Model.Retry(out var source))
        {
            Render();
            RunRequested?.Invoke(source);
        }
    }

    private void StartRun(SourceKind? source)
    {
        if (!Model.Start(source)) return;
        Render();
        RunRequested?.Invoke(source);
    }

    // ---- drawing ----

    private void Render()
    {
        var stage = Model.Stage;
        IsVisible = Model.Visible;
        _line.Text = Model.Line;
        AutomationProperties.SetName(_main, Model.Line);
        _detail.Text = Model.Detail ?? "";
        _detail.IsVisible = Model.Detail != null;

        var offering = stage is BringOverStage.Offer or BringOverStage.Choosing;
        _main.IsHitTestVisible = _main.Focusable = offering;
        _chevron.IsVisible = offering && Model.OneSource == null;
        _chevron.RenderTransform = stage == BringOverStage.Choosing ? Turned : null;
        _choice.IsVisible = stage == BringOverStage.Choosing;

        // The one colour: the glyph. Accent while it offers or runs, success when it came over, warning when it didn't.
        (_lead.Glyph, _lead.Brush) = stage switch
        {
            BringOverStage.Done => (Icons.Check, (IBrush)Palette.Success),
            BringOverStage.Failed => (Icons.Alert, (IBrush)Palette.Warning),
            _ => (Icons.ImportIn, (IBrush)Palette.Accent),
        };

        _action.IsVisible = stage is BringOverStage.Running or BringOverStage.Failed;
        _actionText.Text = stage == BringOverStage.Running ? "Stop" : "Try again";
        if (stage != BringOverStage.Running) _action.IsEnabled = true;
        _close.IsVisible = stage != BringOverStage.Running;
        var close = offering ? ImportPrompt.NoThanksLabel : "Close";
        ToolTip.SetTip(_close, close);
        AutomationProperties.SetName(_close, close);

        _rail.IsVisible = Model.Fraction != null;
        _railFill.RenderTransform = TransformOperations.Parse(
            "scaleX(" + (Model.Fraction ?? 0).ToString("0.###", CultureInfo.InvariantCulture) + ")");
    }

    // One row per launcher, most setups first: its real logo (or initial) in a round well, its name, how many setups.
    private void BuildRows()
    {
        _rows.Children.Clear();
        foreach (var source in Model.Sources)
        {
            var kind = source.Kind;
            var row = Row(ImportParts.RoundLogo(kind, Sizes.ControlSm), source.Name, ImportLogic.Plural(source.Setups, "setup", "setups"));
            AutomationProperties.SetName(row, ImportPrompt.OneLabel(source.Name));
            row.Click += (_, _) => StartRun(kind);
            _rows.Children.Add(row);
        }
    }

    private static Pressable Row(Control well, string name, string? count)
    {
        well.Margin = new Thickness(0, 0, 10, 0);
        var face = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto") };
        face.Children.Add(well.At(0));
        face.Children.Add(Ui.BodyStrong(name).Align(HorizontalAlignment.Left).At(1));
        if (count != null) face.Children.Add(Ui.Caption(count, Palette.TextMuted).Margin(16, 0, 0, 0).At(2));
        return new Pressable(Look.Row, face)
        {
            MinHeight = Sizes.ControlXl, Padding = new Thickness(10, 0, 12, 0), CornerRadius = new CornerRadius(Radius.Sm),
        };
    }

    // "All of them": the import glyph in the same round well the launcher rows wear.
    private static Border ImportPartsWell() => new()
    {
        Width = Sizes.ControlSm, Height = Sizes.ControlSm, CornerRadius = new CornerRadius(Sizes.ControlSm / 2), Background = Palette.Bg,
        BorderBrush = Palette.Line, BorderThickness = new Thickness(1), VerticalAlignment = VerticalAlignment.Center,
        Child = Ui.Icon(Icons.ImportIn, Sizes.IconSm, Palette.Accent).Align(HorizontalAlignment.Center),
    };

    // ---- render runs: the states without a real transfer ----

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
