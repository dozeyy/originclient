using System.Windows;
using System.Windows.Controls;

namespace OriginLauncher.App.UI.Windows;

// A dead-simple, self-contained error dialog. Shows a human-readable summary
// plus a copy-paste-ready technical report (exception type, message, stack,
// environment) so the user can search it or hand it off. The normal layout is
// themed XAML; if the theme is what broke (InitializeComponent throws), the same
// content is built in code with literal colours (CrashFallback), so the player
// always gets a dialog.
public partial class CrashWindow : Window
{
    /// <summary>True when the themed layout could not load and the plain in-code one is showing.</summary>
    public bool UsingFallbackLayout { get; }

    public CrashWindow(string report, string? savedPath, bool fatal)
    {
        try
        {
            InitializeComponent();
        }
        catch (Exception)
        {
            UsingFallbackLayout = true;
            BuildFallbackLayout();
        }
        DetailsBox.Text = report;

        Heading.Text = fatal
            ? "Origin Launcher has to close"
            : "Origin Launcher ran into a problem";
        Summary.Text = fatal
            ? "Something went wrong and Origin has to close. Your worlds, mods and settings are safe: open Origin again to keep playing. If it keeps happening, press Copy details and include them when you ask for help."
            : "Something went wrong, but Origin recovered and is still open. Your worlds, mods and settings are safe. If it keeps happening, press Copy details and include them when you ask for help.";
        CloseButton.Content = fatal ? "Close" : "Continue";

        if (!string.IsNullOrEmpty(savedPath))
            SavedTo.Text = $"A copy is saved in Origin's logs folder ({System.IO.Path.GetFileName(savedPath)}).";
        else
            SavedTo.Visibility = Visibility.Collapsed;
    }

    // The same controls as CrashWindow.xaml (same field names), with no resource lookups.
    private void BuildFallbackLayout()
    {
        var grid = CrashFallback.Frame(this, starRow: 2, rows: 5);
        Heading = CrashFallback.Heading("Origin Launcher ran into a problem");
        Summary = CrashFallback.Body(new Thickness(0, 8, 0, 0));
        Grid.SetRow(Summary, 1);
        DetailsBox = CrashFallback.Details(grid, 2, new Thickness(0, 16, 0, 0), 12);
        SavedTo = CrashFallback.Caption(new Thickness(0, 8, 0, 0));
        Grid.SetRow(SavedTo, 3);
        grid.Children.Add(Heading);
        grid.Children.Add(Summary);
        grid.Children.Add(SavedTo);
        var buttons = CrashFallback.ButtonRow(grid, 4);
        CopyButton = CrashFallback.Btn("Copy details", primary: false, Copy_Click);
        CopyButton.Margin = new Thickness(0, 0, 8, 0);
        CloseButton = CrashFallback.Btn("Close", primary: true, Close_Click);
        buttons.Children.Add(CopyButton);
        buttons.Children.Add(CloseButton);
    }

    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(DetailsBox.Text);
            CopyButton.Content = "Copied";
        }
        catch
        {
            // Clipboard can transiently fail (another app holding it) — leave the
            // text on screen so the user can still select and copy manually.
            CopyButton.Content = "Press Ctrl+C";
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
