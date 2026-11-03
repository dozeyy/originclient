using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Styling;

namespace OriginLauncher.Mac.UI.Controls;

/// <summary>
/// The launcher's own confirm dialog (twin of the WPF ConfirmOverlay), never a system alert. One prompt at a time: a
/// newer call answers the older with "no" (newest action wins).
/// </summary>
public sealed partial class ConfirmOverlay : UserControl
{
    private TaskCompletionSource<bool>? _tcs;
    private bool _destructive;

    public ConfirmOverlay()
    {
        InitializeComponent();
        PrimaryButton.Click += (_, _) => Resolve(true);
        SecondaryButton.Click += (_, _) => Resolve(false);
        // A key held down when the dialog opens (the Enter that picked "Remove") keeps repeating: those repeats must
        // never confirm or press a button. Tunnel, so the focused button never sees them either.
        AddHandler(KeyDownEvent, (_, e) =>
        {
            if (IsVisible && e.KeyModifiers == KeyModifiers.None && IsRepeat(e) && e.Key is Key.Enter or Key.Space)
                e.Handled = true;
        }, RoutingStrategies.Tunnel);
    }

    /// <summary>
    /// Shows the prompt and awaits the choice: true for the primary button, false for the secondary (or Esc).
    /// A destructive prompt (Remove, Block, Kick) starts on Cancel, so a double Enter never confirms it;
    /// <paramref name="focusSecondary"/> does the same without the red button.
    /// </summary>
    public Task<bool> ShowAsync(string title, string message, string primaryText, string secondaryText,
        bool destructive = false, bool focusSecondary = false)
    {
        _tcs?.TrySetResult(false);
        TitleText.Text = title;
        MessageText.Text = message;
        PrimaryButton.Content = primaryText;
        PrimaryButton.Theme = (ControlTheme)this.FindResource(destructive ? "Button.Danger" : "Button.Primary.Medium")!;
        SecondaryButton.Content = secondaryText;
        _destructive = destructive;
        _tcs = new TaskCompletionSource<bool>();
        _opened = DateTime.UtcNow;
        IsVisible = true;
        (destructive || focusSecondary ? SecondaryButton : PrimaryButton).Focus();
        return _tcs.Task;
    }

    /// <summary>Closes an open prompt as "no".</summary>
    public void Dismiss()
    {
        if (IsVisible) Resolve(false);
    }

    private void Resolve(bool result)
    {
        IsVisible = false;
        _tcs?.TrySetResult(result);
        _tcs = null;
    }

    // Avalonia's KeyEventArgs has no IsRepeat: a key-down that arrives within the OS repeat delay of the dialog
    // opening is the held key from before it opened (macOS's shortest initial repeat delay is well above 150 ms).
    private DateTime _opened;
    private bool IsRepeat(KeyEventArgs e) => (DateTime.UtcNow - _opened).TotalMilliseconds < 150;

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
            Resolve(false);
        }
        else if (e.Key == Key.Enter)
        {
            e.Handled = true;
            Resolve(PrimaryButton.IsFocused || (!SecondaryButton.IsFocused && !_destructive));
        }
        else base.OnKeyDown(e);
    }
}
