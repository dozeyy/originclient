using Avalonia.Controls;
using Avalonia.Input;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>The player's answer to the Fix Compatibility prompt.</summary>
public enum FixChoice
{
    Cancel,
    LaunchAnyway,
    Fix,
}

/// <summary>
/// The pre-launch Fix Compatibility prompt (twin of the WPF FixCompatibilityOverlay). One job: show what Play refused and
/// what the fix would change, and return the answer. It never plans, writes or launches anything itself (HomePage does,
/// with Core/Components/FixCompatibility). One prompt at a time; a newer one answers the older with Cancel.
/// </summary>
public sealed partial class FixCompatibilityOverlay : UserControl
{
    private TaskCompletionSource<FixChoice>? _tcs;

    public FixCompatibilityOverlay()
    {
        InitializeComponent();
        CancelButton.Click += (_, _) => Resolve(FixChoice.Cancel);
        AnywayButton.Click += (_, _) => Resolve(FixChoice.LaunchAnyway);
        FixButton.Click += (_, _) => Resolve(FixChoice.Fix);
        // Leaving Home while it is open (the page leaves the window's page host) answers it with Cancel, the same as Esc.
        DetachedFromVisualTree += (_, _) => Dismiss();
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
        NoFixHeading.IsVisible = false;
        FootText.Text = "";
        FootText.IsVisible = false;
        ChangeList.IsVisible = true;
        FixButton.IsEnabled = false;
        FixButton.IsVisible = true;
        _tcs = new TaskCompletionSource<FixChoice>();
        IsVisible = true;
        CancelButton.Focus();
        return _tcs.Task;
    }

    /// <summary>Shows the plan. <paramref name="canFix"/> false = there is nothing to click; the lines say why.</summary>
    public void ShowPlan(string heading, IReadOnlyList<string> changes, IReadOnlyList<string> noFix, string foot, bool canFix)
    {
        if (!IsVisible) return;
        PlanHeading.Text = heading;
        ChangeList.ItemsSource = changes.Select(c => "- " + c).ToList();
        NoFixList.ItemsSource = noFix.Select(c => "- " + c).ToList();
        NoFixHeading.IsVisible = canFix && noFix.Count > 0;
        FootText.Text = foot;
        FootText.IsVisible = foot.Length > 0;
        ChangeList.IsVisible = changes.Count > 0;
        FixButton.IsEnabled = canFix;
        FixButton.IsVisible = canFix;
        if (canFix) FixButton.Focus();
        else AnywayButton.Focus();
    }

    public void Dismiss()
    {
        if (IsVisible) Resolve(FixChoice.Cancel);
    }

    private void Resolve(FixChoice choice)
    {
        IsVisible = false;
        _tcs?.TrySetResult(choice);
        _tcs = null;
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (!IsVisible)
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
            Resolve(FixButton.IsFocused && FixButton.IsEnabled ? FixChoice.Fix
                : AnywayButton.IsFocused ? FixChoice.LaunchAnyway
                : FixChoice.Cancel);
        }
        else base.OnKeyDown(e);
    }
}
