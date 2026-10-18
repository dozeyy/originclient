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
    public Task<bool> ShowAsync(string title, string message, string primaryText, string secondaryText,
        bool destructive = false)
    {
        _tcs?.TrySetResult(false);

        TitleText.Text = title;
        MessageText.Text = message;
        PrimaryButton.Content = primaryText;
        // Remove / Block / Kick confirm in redstone, not the launcher's emerald "go" colour.
        PrimaryButton.Style = (Style)FindResource(destructive ? "Button.Danger" : "Button.Primary");
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

    /// <summary>Closes an open prompt as "no" (Esc handled by the window before it reaches here).</summary>
    public void Dismiss()
    {
        if (Visibility == Visibility.Visible) Resolve(false);
    }

    private void PrimaryButton_Click(object sender, RoutedEventArgs e) => Resolve(true);

    private void SecondaryButton_Click(object sender, RoutedEventArgs e) => Resolve(false);

    // A key held down when the dialog opens (the Enter that picked "Remove" from a menu) keeps
    // sending repeats: those must never confirm or press a button.
    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        if (Visibility == Visibility.Visible && e.IsRepeat && e.Key is Key.Enter or Key.Space)
        {
            e.Handled = true;
            return;
        }
        base.OnPreviewKeyDown(e);
    }

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
