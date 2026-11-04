using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using OriginSetup.Engine;

namespace OriginSetup.UI;

// Confirm -> Removing -> Done (or Problem): the installer's window (spec 14) with uninstall wording. "Keep
// Origin" is the default (Enter, and the first focus) and the data checkbox starts OFF: uninstalling never
// deletes worlds unless the player ticks it.
public partial class UninstallWindow : Window
{
    private readonly string _dir;
    private readonly FrameworkElement[] _pages;
    private bool _working;

    public UninstallWindow(string installDir)
    {
        InitializeComponent();
        WindowFrame.RoundCorners(this);
        _dir = installDir;
        _pages = new FrameworkElement[] { ConfirmPage, RemovingPage, DonePage, ProblemPage };
        Loaded += (_, _) => KeepButton.Focus(); // the safe action (spec 14)
    }

    // Ticking "also delete my worlds..." says so on the button itself, so the final click is never a surprise.
    private void RemoveDataCheck_Changed(object sender, RoutedEventArgs e)
    {
        UninstallButton.Content = RemoveDataCheck.IsChecked == true ? "Uninstall and delete data" : "Uninstall";
        if (RemoveDataCheck.IsChecked != true) ShowRunningNote(Array.Empty<string>()); // only the data box needs the game closed
    }

    private void Keep_Click(object sender, RoutedEventArgs e) => Close();

    private async void Uninstall_Click(object sender, RoutedEventArgs e)
    {
        if (_working) return;
        bool removeData = RemoveDataCheck.IsChecked == true;
        // Deleting the data under a running game would half-delete its world: ask the player to close it first and
        // stay on this page (pressing Uninstall again re-checks). Uninstaller.Run refuses too, as a backstop.
        if (removeData)
        {
            var open = await Task.Run(() => RunningGames.In(Handoff.DataRoot));
            ShowRunningNote(open);
            if (open.Count > 0) return;
        }
        _working = true;
        Bar.CanClose = false;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = 0;
        ShowPage(RemovingPage);
        UpdateLayout();
        RenderProgress(0, removeData);

        UninstallResult result;
        try
        {
            result = await Task.Run(() => Uninstaller.Run(_dir, removeData,
                f => Dispatcher.BeginInvoke(new Action(() => RenderProgress(f, removeData)))));
        }
        catch (Exception ex)
        {
            SetupLog.Write("uninstall failed: " + ex);
            result = new UninstallResult(installFolderLeft: true, dataLeft: removeData);
        }
        bool ok = result.Ok;

        if (ok) await Task.Delay(450); // let the bar land before the page changes, like the installer
        _working = false;
        Bar.CanClose = true;
        if (ok)
        {
            ShowDone(keptData: !removeData);
            DoneCloseButton.IsDefault = true;
        }
        else
        {
            ShowProblem(result);
            ProblemCloseButton.IsDefault = true;
        }
    }

    private void ShowRunningNote(IReadOnlyList<string> versionsOpen)
    {
        RunningNote.Text = RunningGames.CloseFirstMessage(versionsOpen);
        RunningNote.Visibility = versionsOpen.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ShowDone(bool keptData)
    {
        DoneBody.Text = keptData
            ? "Your accounts, settings and worlds were kept. Reinstall any time to pick up where you left off."
            : "Everything is gone, including your Origin data. Thanks for playing.";
        var visible = keptData ? Visibility.Visible : Visibility.Collapsed;
        KeptCaption.Visibility = visible;
        KeptPath.Visibility = visible;
        ShowPage(DonePage);
        Dispatcher.BeginInvoke(new Action(() => WindowFrame.FitPath(KeptPath, Handoff.DataRoot)),
            System.Windows.Threading.DispatcherPriority.Loaded);
    }

    private void KeptPath_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (e.WidthChanged) WindowFrame.FitPath(KeptPath, Handoff.DataRoot);
    }

    // The folder the Problem page names: the one actually left over (the data folder when the player's data was
    // what couldn't go; before 2026-11-04 it always named the install folder, which had usually gone fine).
    private string _problemPath = "";

    private void ShowProblem(UninstallResult result)
    {
        ProblemBody.Text = result.ProblemText(_dir);
        _problemPath = result.DataLeft ? Handoff.DataRoot : _dir;
        ShowPage(ProblemPage);
        Dispatcher.BeginInvoke(new Action(() => WindowFrame.FitPath(ProblemPath, _problemPath)),
            System.Windows.Threading.DispatcherPriority.Loaded);
    }

    private void ProblemPath_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (e.WidthChanged) WindowFrame.FitPath(ProblemPath, _problemPath);
    }

    // Same rail + step line + percent as the installer's; the wording follows what Uninstaller.Run is doing.
    private void RenderProgress(double f, bool removeData)
    {
        f = Math.Max(0, Math.Min(1, f));
        PageMotion.Width(ProgressFill, ProgressTrack.ActualWidth * f);
        PercentText.Text = $"{f * 100:0}%";
        StageText.Text = f < 0.2 ? "Closing Origin"
            : f < 0.4 ? "Removing shortcuts"
            : f < 0.75 ? "Removing Origin files"
            : f < 1 ? (removeData ? "Removing your Origin data" : "Finishing up")
            : "Done";
    }

    private void ShowPage(FrameworkElement page) => PageMotion.Show(page, _pages);

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Bar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && !_working) Close();
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_working) e.Cancel = true; // a half-removed install is worse than waiting a second
    }

    // ---------- CI screenshot tour (RenderTour.cs) ----------

    internal void TourConfirm(bool removeData, bool keyboardFocus, IReadOnlyList<string>? gamesOpen = null)
    {
        ShowPage(ConfirmPage);
        RemoveDataCheck.IsChecked = removeData;
        ShowRunningNote(gamesOpen ?? Array.Empty<string>());
        Chrome.SetKeyFocus(KeepButton, keyboardFocus);
    }

    internal void TourRemoving(double fraction, bool removeData)
    {
        ShowPage(RemovingPage);
        UpdateLayout();
        RenderProgress(fraction, removeData);
    }

    internal void TourDone(bool keptData) => ShowDone(keptData);

    internal void TourProblem() => ShowProblem(new UninstallResult(installFolderLeft: true, dataLeft: false));

    internal void TourProblemGameOpen() =>
        ShowProblem(new UninstallResult(installFolderLeft: false, dataLeft: true, new[] { "1.21.1" }));
}
