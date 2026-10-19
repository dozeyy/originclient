using System.Windows;
using System.Windows.Controls;
using OriginLauncher.App.Core.Import;

namespace OriginLauncher.App.UI.Controls;

public partial class ImportPromptCard : UserControl
{
    /// <summary>The player pressed Import (the card has already hidden itself).</summary>
    public event EventHandler? ImportRequested;

    public ImportPromptCard()
    {
        InitializeComponent();
    }

    // The question's words come from Core's ImportPrompt.Question (one source): MainWindow passes them to ShowQuestion.

    private bool _pending; // the question is waiting for an answer
    private bool _refused; // Import would be refused right now (a game starting, an update): the card stays out

    public void ShowQuestion(string question)
    {
        QuestionText.Text = question;
        System.Windows.Automation.AutomationProperties.SetName(this, question);
        _pending = true;
        Visibility = _refused ? Visibility.Collapsed : Visibility.Visible;
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

    private void NotNow_Click(object sender, RoutedEventArgs e) => Hide();

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        ImportRequested?.Invoke(this, EventArgs.Empty);
    }
}
