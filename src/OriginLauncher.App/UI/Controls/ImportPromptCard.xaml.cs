using System.Windows;
using System.Windows.Controls;

namespace OriginLauncher.App.UI.Controls;

public partial class ImportPromptCard : UserControl
{
    /// <summary>The player pressed Import (the card has already hidden itself).</summary>
    public event EventHandler? ImportRequested;

    public ImportPromptCard()
    {
        InitializeComponent();
    }

    public void ShowQuestion(string question)
    {
        QuestionText.Text = question;
        System.Windows.Automation.AutomationProperties.SetName(this, question);
        Visibility = Visibility.Visible;
    }

    public void Hide() => Visibility = Visibility.Collapsed;

    private void NotNow_Click(object sender, RoutedEventArgs e) => Hide();

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        Hide();
        ImportRequested?.Invoke(this, EventArgs.Empty);
    }
}
