using Avalonia.Controls;
using Avalonia.Input.Platform;

namespace OriginLauncher.Mac.UI.Windows;

/// <summary>
/// The launcher's own error window (twin of the WPF UI/Windows/CrashWindow): what happened in plain words, the report,
/// Copy details and Close (Continue when the launcher recovered). Shown by MacCrashReporter.
/// </summary>
public sealed partial class CrashWindow : Window
{
    public CrashWindow() : this("", null, fatal: false) { }

    public CrashWindow(string report, string? savedPath, bool fatal)
    {
        InitializeComponent();
        DetailsBox.Text = report;
        Heading.Text = fatal ? "Origin Launcher has to close" : "Origin Launcher ran into a problem";
        Summary.Text = fatal
            ? "An unexpected error means the launcher can't keep running. The details below explain exactly what happened. Click Copy details, then paste them into a search or send them along so it can be fixed."
            : "Something went wrong, but the launcher recovered and is still open. The details below explain exactly what happened. Click Copy details, then paste them into a search or send them along so it can be fixed.";
        CloseButton.Content = fatal ? "Close" : "Continue";
        if (!string.IsNullOrEmpty(savedPath)) SavedTo.Text = $"A copy of this report was saved to:\n{savedPath}";
        else SavedTo.IsVisible = false;
        CopyButton.Click += async (_, _) =>
        {
            try
            {
                if (Clipboard != null) await Clipboard.SetTextAsync(report);
                CopyButton.Content = "Copied!";
            }
            catch
            {
                CopyButton.Content = "Press Cmd+C";
            }
        };
        CloseButton.Click += (_, _) => Close();
    }
}
