using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace OriginLauncher.App.UI.Controls;

public partial class ConfirmOverlay : UserControl
{
    private TaskCompletionSource<bool>? _tcs;

    public ConfirmOverlay()
    {
        InitializeComponent();
    }

    /// <summary>
    /// Show a modal confirm and await the choice: true if the primary button is
    /// clicked, false if the secondary button (or Esc) dismisses it. Only one
    /// prompt is live at a time; a second call supersedes the first (resolves it
    /// false), matching the launcher's "newest action wins" rule.
    /// </summary>
    public Task<bool> ShowAsync(string title, string message, string primaryText, string secondaryText)
    {
        _tcs?.TrySetResult(false);

        TitleText.Text = title;
        MessageText.Text = message;
        PrimaryButton.Content = primaryText;
        SecondaryButton.Content = secondaryText;

        _tcs = new TaskCompletionSource<bool>();
        Visibility = Visibility.Visible;
        Focus();
        // Focus the primary action so Enter confirms and Esc cancels.
        PrimaryButton.Focus();
        return _tcs.Task;
    }

    private void Resolve(bool result)
    {
        Visibility = Visibility.Collapsed;
        _tcs?.TrySetResult(result);
        _tcs = null;
    }

    private void PrimaryButton_Click(object sender, RoutedEventArgs e) => Resolve(true);

    private void SecondaryButton_Click(object sender, RoutedEventArgs e) => Resolve(false);

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (Visibility != Visibility.Visible)
        {
            base.OnKeyDown(e);
            return;
        }

        if (e.Key == Key.Escape)
        {
            e.Handled = true;
            Resolve(false);
        }
        else if (e.Key == Key.Enter)
        {
            e.Handled = true;
            Resolve(true);
        }
        else
        {
            base.OnKeyDown(e);
        }
    }
}
