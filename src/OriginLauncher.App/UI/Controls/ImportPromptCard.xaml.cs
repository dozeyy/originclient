using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using OriginLauncher.App.Core.Import;

namespace OriginLauncher.App.UI.Controls;

public partial class ImportPromptCard : UserControl
{
    /// <summary>The player pressed "Import everything" (the card has already hidden itself).</summary>
    public event EventHandler? ImportRequested;

    /// <summary>The player pressed "Choose": the Import page opens on its pick screen.</summary>
    public event EventHandler? ChooseRequested;

    // At most this many launcher chips; the rest fold into one "+N more" chip.
    private const int MaxChips = 5;

    public ImportPromptCard()
    {
        InitializeComponent();
    }

    // The question's words come from Core's ImportPrompt.Question (one source): MainWindow passes them to ShowQuestion,
    // with the qualifying setups so the card can name their launchers.

    private bool _pending; // the question is waiting for an answer
    private bool _refused; // Import would be refused right now (a game starting, an update): the card stays out

    public void ShowQuestion(string question, IReadOnlyCollection<DetectedInstance>? setups = null)
    {
        QuestionText.Text = question;
        System.Windows.Automation.AutomationProperties.SetName(this, question);
        BuildChips(setups);
        var wasVisible = Visibility == Visibility.Visible;
        _pending = true;
        Visibility = _refused ? Visibility.Collapsed : Visibility.Visible;
        if (!wasVisible && Visibility == Visibility.Visible) Enter();
    }

    /// <summary>The sentence from the wiring's refusal check, or null when Import can run: hides the card while it can't, shows it again after.</summary>
    public void SetRefusal(string? sentence)
    {
        _refused = sentence != null;
        Visibility = _pending && !_refused ? Visibility.Visible : Visibility.Collapsed;
    }

    public void Hide()
    {
        _pending = false;
        Visibility = Visibility.Collapsed;
    }

    // Rise in (opacity + translate only): the Workbench dialog enter, 220 ms (Motion.Enter) and an 8 px lift. It never
    // blocks input: the buttons work from the first frame.
    private void Enter()
    {
        if (Motion.Off) return;
        Card.BeginAnimation(OpacityProperty, Motion.FromTo(0, 1, 220));
        CardLift.BeginAnimation(TranslateTransform.YProperty, Motion.FromTo(8, 0, 220, Motion.Out));
    }

    // One chip per launcher, most setups first: its real logo (or initial), its name, and how many setups it holds.
    private void BuildChips(IReadOnlyCollection<DetectedInstance>? setups)
    {
        LaunchersPanel.Children.Clear();
        if (setups is null || setups.Count == 0)
        {
            LaunchersPanel.Visibility = Visibility.Collapsed;
            return;
        }
        LaunchersPanel.Visibility = Visibility.Visible;
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

    private static Border Chip(ImageSource? logo, string initial, string name, string? detail)
    {
        var well = new Border
        {
            Width = 28,
            Height = 28,
            Margin = new Thickness(0, 0, 10, 0),
            // A round well inside the capsule chip, concentric with its ends.
            CornerRadius = (CornerRadius)Application.Current.FindResource("Radius.Full"),
            Background = (Brush)Application.Current.FindResource("Brush.Bg"),
            Child = logo != null
                ? new Image { Source = logo, Width = 18, Height = 18 }
                : new TextBlock
                {
                    Text = initial,
                    FontSize = (double)Application.Current.FindResource("FontSize.Caption"),
                    FontWeight = FontWeights.Bold,
                    Foreground = (Brush)Application.Current.FindResource("Brush.Text"),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                },
        };
        if (logo != null) RenderOptions.SetBitmapScalingMode(well.Child, BitmapScalingMode.HighQuality);

        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(well);
        row.Children.Add(new TextBlock
        {
            Text = name,
            FontSize = (double)Application.Current.FindResource("FontSize.Body"),
            FontWeight = FontWeights.SemiBold,
            Foreground = (Brush)Application.Current.FindResource("Brush.Text"),
            VerticalAlignment = VerticalAlignment.Center,
        });
        if (detail != null)
            row.Children.Add(new TextBlock
            {
                Text = detail,
                FontSize = (double)Application.Current.FindResource("FontSize.Caption"),
                Margin = new Thickness(8, 0, 0, 0),
                Foreground = (Brush)Application.Current.FindResource("Brush.TextMuted"),
                VerticalAlignment = VerticalAlignment.Center,
            });

        // A capsule chip (every chip is one: radius = height / 2): 6 round the 28 well, 14 after the text so the
        // text clears the round end.
        var chip = new Border
        {
            Padding = new Thickness(6, 6, 14, 6),
            Margin = new Thickness(0, 0, 8, 8),
            CornerRadius = (CornerRadius)Application.Current.FindResource("Radius.Full"),
            Background = (Brush)Application.Current.FindResource("Brush.Card"),
            BorderBrush = (Brush)Application.Current.FindResource("Brush.SoftStroke"),
            BorderThickness = new Thickness(1),
            Child = row,
        };
        Capsule.SetOn(chip, true); // radius = height / 2: a capsule, never an oval
        return chip;
    }

    private void NotNow_Click(object sender, RoutedEventArgs e) => Hide();

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        ImportRequested?.Invoke(this, EventArgs.Empty);
    }

    private void Choose_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        ChooseRequested?.Invoke(this, EventArgs.Empty);
    }
}
