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

    /// <summary>
    /// "Bring over your Minecraft setups?", or for exactly one setup "Bring over your setup from Prism Launcher?".
    /// (The card words its own question; Core's ImportPrompt only decides whether to show it.)
    /// </summary>
    public static string QuestionFor(IReadOnlyCollection<DetectedInstance> setups)
    {
        if (setups.Count != 1) return "Bring over your Minecraft setups?";
        var from = setups.First().Kind switch
        {
            SourceKind.OfficialLauncher => "the Minecraft launcher",
            SourceKind.Prism => "Prism Launcher",
            SourceKind.Modrinth => "the Modrinth App",
            SourceKind.CurseForge => "CurseForge",
            _ => "another launcher",
        };
        return $"Bring over your setup from {from}?";
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
