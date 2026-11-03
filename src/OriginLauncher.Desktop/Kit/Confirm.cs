using System;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Threading;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

// The launcher's own confirm dialog (WPF UI/Controls/ConfirmOverlay, spec 11.10): title, dim body text, then a
// right-aligned footer, the secondary (cancel) first, then the primary or danger button, both lg 36 and at least
// 96 wide. Primary returns true; Secondary, Esc and click-out return false. The safe action holds the focus.
public static class Confirm
{
    public static Task<bool> AskAsync(MainWindow window, string title, string message, string primary,
        string secondary = "Cancel", bool danger = false)
    {
        var done = new TaskCompletionSource<bool>();
        var result = false;

        var titleText = Ui.Wrap(Ui.Title(title));
        var body = Ui.Body(message);
        body.Margin = new Thickness(0, 10, 0, 0);

        var cancel = Ui.Button(secondary, Look.Secondary);
        cancel.MinWidth = 96;
        var ok = Ui.Button(primary, danger ? Look.Danger : Look.Primary);
        ok.MinWidth = 96;
        var footer = Ui.Row(Space.S2, cancel, ok);
        footer.HorizontalAlignment = HorizontalAlignment.Right;
        footer.Margin = new Thickness(0, Space.S6, 0, 0);

        cancel.Click += (_, _) => { result = false; window.CloseDialog(); };
        ok.Click += (_, _) => { result = true; window.CloseDialog(); };

        window.OpenDialog(Ui.Column(0, titleText, body, footer), dismissable: true,
            onClosed: () => done.TrySetResult(result));
        Dispatcher.UIThread.Post(() => cancel.Focus(), DispatcherPriority.Input);
        return done.Task;
    }
}
