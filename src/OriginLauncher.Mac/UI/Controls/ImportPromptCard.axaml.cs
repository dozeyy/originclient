using Avalonia;
using Avalonia.Automation;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using Avalonia.Threading;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Mac.UI;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// Twin of the WPF UI/Controls/ImportPromptCard.xaml.cs: the first-run welcome over Home. Same question (Core's
/// ImportPrompt.Question, passed in by MainWindow), same chips, same three answers.
/// </summary>
public partial class ImportPromptCard : UserControl
{
    /// <summary>The player pressed "Import everything" (the card has already hidden itself).</summary>
    public event EventHandler? ImportRequested;

    /// <summary>The player pressed "Choose": the Import page opens on its pick screen.</summary>
    public event EventHandler? ChooseRequested;

    // At most this many launcher chips; the rest fold into one "+N more" chip.
    private const int MaxChips = 5;

    // The rise in: an 8 px lift (the Workbench dialog enter, timed by Motion.Enter in the axaml's transitions).
    private static readonly ITransform Lifted = TransformOperations.Parse("translateY(8px)");
    private static readonly ITransform Rested = TransformOperations.Parse("translateY(0px)");

    public ImportPromptCard()
    {
        InitializeComponent();
    }

    private bool _pending; // the question is waiting for an answer
    private bool _refused; // Import would be refused right now (a game starting, an update): the card stays out

    public void ShowQuestion(string question, IReadOnlyCollection<DetectedInstance>? setups = null)
    {
        QuestionText.Text = question;
        AutomationProperties.SetName(this, question);
        BuildChips(setups);
        var wasVisible = IsVisible;
        _pending = true;
        IsVisible = !_refused;
        if (!wasVisible && IsVisible) Enter();
    }

    /// <summary>The sentence from the wiring's refusal check, or null when Import can run: hides the card while it can't, shows it again after.</summary>
    public void SetRefusal(string? sentence)
    {
        _refused = sentence != null;
        IsVisible = _pending && !_refused;
    }

    public void Hide()
    {
        _pending = false;
        IsVisible = false;
    }

    // Opacity + translate only, and it never blocks input: the buttons work from the first frame.
    private void Enter()
    {
        if (Motion.Off) return;
        Card.Opacity = 0;
        Card.RenderTransform = Lifted;
        Dispatcher.UIThread.Post(() =>
        {
            Card.Opacity = 1;
            Card.RenderTransform = Rested;
        }, DispatcherPriority.Render);
    }

    // One chip per launcher, most setups first: its real logo (or initial), its name, and how many setups it holds.
    private void BuildChips(IReadOnlyCollection<DetectedInstance>? setups)
    {
        LaunchersPanel.Children.Clear();
        if (setups is null || setups.Count == 0)
        {
            LaunchersPanel.IsVisible = false;
            return;
        }
        LaunchersPanel.IsVisible = true;
        var groups = setups.GroupBy(s => s.Kind)
                           .Select(g => (Kind: g.Key, Count: g.Count()))
                           .OrderByDescending(g => g.Count)
                           .ThenBy(g => ImportFlowModel.LauncherTitle(g.Kind), StringComparer.Ordinal)
                           .ToList();
        var shown = groups.Count > MaxChips ? MaxChips - 1 : groups.Count;
        foreach (var (kind, count) in groups.Take(shown))
            LaunchersPanel.Children.Add(Chip(LauncherLogos.For(kind), ImportLogic.Initial(ImportFlowModel.LauncherTitle(kind)),
                ImportFlowModel.LauncherTitle(kind), count == 1 ? "1 setup" : $"{count} setups"));
        if (groups.Count > shown)
            LaunchersPanel.Children.Add(Chip(null, $"+{groups.Count - shown}", "more", null));
    }

    private Border Chip(IImage? logo, string initial, string name, string? detail)
    {
        Control mark = logo != null
            ? new Image { Source = logo, Width = 18, Height = 18 }
            : new TextBlock
            {
                Text = initial,
                FontSize = Res<double>("FontSize.Caption"),
                FontWeight = FontWeight.Bold,
                Foreground = Res<IBrush>("Brush.Text"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        if (logo != null) RenderOptions.SetBitmapInterpolationMode(mark, Avalonia.Media.Imaging.BitmapInterpolationMode.HighQuality);
        var well = new Border
        {
            Width = 28,
            Height = 28,
            Margin = new Thickness(0, 0, 10, 0),
            // A round well inside the capsule chip, concentric with its ends (half its 28 side: a circle).
            CornerRadius = new CornerRadius(14),
            Background = Res<IBrush>("Brush.Bg"),
            Child = mark,
        };

        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(well);
        row.Children.Add(new TextBlock
        {
            Text = name,
            FontFamily = Res<FontFamily>("Font.Primary") ?? FontFamily.Default,
            FontSize = Res<double>("FontSize.Body"),
            FontWeight = FontWeight.SemiBold,
            Foreground = Res<IBrush>("Brush.Text"),
            VerticalAlignment = VerticalAlignment.Center,
        });
        if (detail != null)
            row.Children.Add(new TextBlock
            {
                Text = detail,
                FontFamily = Res<FontFamily>("Font.Primary") ?? FontFamily.Default,
                FontSize = Res<double>("FontSize.Caption"),
                Margin = new Thickness(8, 0, 0, 0),
                Foreground = Res<IBrush>("Brush.TextMuted"),
                VerticalAlignment = VerticalAlignment.Center,
            });

        // A capsule chip (every chip is one: radius = height / 2): 6 round the 28 well, 14 after the text so the
        // text clears the round end.
        var chip = new Border
        {
            Padding = new Thickness(6, 6, 14, 6),
            Margin = new Thickness(0, 0, 8, 8),
            Background = Res<IBrush>("Brush.Card"),
            BorderBrush = Res<IBrush>("Brush.SoftStroke"),
            BorderThickness = new Thickness(1),
            Child = row,
        };
        Capsule.SetOn(chip, true); // radius = height / 2: a capsule, never an oval
        return chip;
    }

    private T Res<T>(string key) =>
        (this.TryFindResource(key, out var v) || Application.Current!.TryGetResource(key, null, out v)) && v is T t ? t : default!;

    private void NotNow_Click(object? sender, RoutedEventArgs e) => Hide();

    private void Import_Click(object? sender, RoutedEventArgs e)
    {
        Hide();
        ImportRequested?.Invoke(this, EventArgs.Empty);
    }

    private void Choose_Click(object? sender, RoutedEventArgs e)
    {
        Hide();
        ChooseRequested?.Invoke(this, EventArgs.Empty);
    }
}
