using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace OriginLauncher.App.UI.Controls;

/// <summary>The player's answer to the Fix Compatibility prompt.</summary>
public enum FixChoice
{
    Cancel,
    LaunchAnyway,
    Fix,
}

/// <summary>
/// The pre-launch Fix Compatibility prompt. One job: show what Play refused and what the fix would change, and return
/// the player's answer. It never plans, writes or launches anything itself (HomePage does, with
/// Core/Components/FixCompatibility). Only one prompt is live at a time; a newer one answers the older with Cancel.
/// </summary>
public partial class FixCompatibilityOverlay : UserControl
{
    private TaskCompletionSource<FixChoice>? _tcs;

    public FixCompatibilityOverlay()
    {
        InitializeComponent();
        // Leaving Home while the prompt is open (Ctrl+1..4, a nav click) answers it with Cancel, the same as Esc, so
        // no answer is left pending on a page the player can't see.
        IsVisibleChanged += (_, e) =>
        {
            if (e.NewValue is false) Dismiss();
        };
    }

    /// <summary>Opens the prompt in its "looking for a fix" state and waits for the answer.</summary>
    public Task<FixChoice> ShowAsync(string title, string message)
    {
        _tcs?.TrySetResult(FixChoice.Cancel);
        TitleText.Text = title;
        MessageText.Text = message;
        PlanHeading.Text = "Looking for a fix...";
        ChangeList.ItemsSource = null;
        NoFixList.ItemsSource = null;
        NoFixHeading.Visibility = Visibility.Collapsed;
        FootText.Text = "";
        FootText.Visibility = Visibility.Collapsed;
        ChangeList.Visibility = Visibility.Visible;
        FixButton.IsEnabled = false;
        FixButton.Visibility = Visibility.Visible;

        _tcs = new TaskCompletionSource<FixChoice>();
        Visibility = Visibility.Visible;
        Focus();
        CancelButton.Focus();
        return _tcs.Task;
    }

    /// <summary>Shows the plan. <paramref name="canFix"/> false = there is nothing to click; the lines say why.</summary>
    public void ShowPlan(string heading, IReadOnlyList<string> changes, IReadOnlyList<string> noFix, string foot, bool canFix)
    {
        if (Visibility != Visibility.Visible) return;
        PlanHeading.Text = heading;
        ChangeList.ItemsSource = changes.Select(c => "- " + c).ToList();
        NoFixList.ItemsSource = noFix.Select(c => "- " + c).ToList();
        NoFixHeading.Visibility = canFix && noFix.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        FootText.Text = foot;
        FootText.Visibility = foot.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
        ChangeList.Visibility = changes.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        FixButton.IsEnabled = canFix;
        FixButton.Visibility = canFix ? Visibility.Visible : Visibility.Collapsed;
        if (canFix) FixButton.Focus();
        else AnywayButton.Focus();
    }

    /// <summary>Closes an open prompt as Cancel.</summary>
    public void Dismiss()
    {
        if (Visibility == Visibility.Visible) Resolve(FixChoice.Cancel);
    }

    private void Resolve(FixChoice choice)
    {
        Visibility = Visibility.Collapsed;
        _tcs?.TrySetResult(choice);
        _tcs = null;
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e) => Resolve(FixChoice.Cancel);

    private void AnywayButton_Click(object sender, RoutedEventArgs e) => Resolve(FixChoice.LaunchAnyway);

    private void FixButton_Click(object sender, RoutedEventArgs e) => Resolve(FixChoice.Fix);

    // The Enter that pressed Play may still be repeating when the prompt opens: repeats never press anything.
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
            Resolve(FixChoice.Cancel);
        }
        else if (e.Key == Key.Enter)
        {
            e.Handled = true;
            Resolve(FixButton.IsKeyboardFocused && FixButton.IsEnabled ? FixChoice.Fix
                : AnywayButton.IsKeyboardFocused ? FixChoice.LaunchAnyway
                : FixChoice.Cancel);
        }
        else
        {
            base.OnKeyDown(e);
        }
    }
}
