using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using OriginSetup.Engine;

namespace OriginSetup.UI;

// Confirm -> Removing -> Done (or Problem). The pages are the installer's own
// (Welcome / Installing / Done / Problem) with uninstall wording: same window
// size, mark, bar, status line, step rail and motion. "Keep Origin" is the
// default (Enter) and the data toggle starts OFF: uninstalling never deletes
// worlds unless asked.
public partial class UninstallWindow : Window
{
    private readonly string _dir;
    private readonly FrameworkElement[] _pages;
    private bool _working;

    public UninstallWindow(string installDir)
    {
        InitializeComponent();
        _dir = installDir;
        _pages = new FrameworkElement[] { ConfirmPage, RemovingPage, DonePage, ProblemPage };
        SetStep(1);
    }

    private void RemoveDataToggle_Changed(object sender, RoutedEventArgs e) =>
        UninstallButton.Content = RemoveDataToggle.IsChecked == true ? "Uninstall and delete data" : "Uninstall";

    private void Keep_Click(object sender, RoutedEventArgs e) => Close();

    private async void Uninstall_Click(object sender, RoutedEventArgs e)
    {
        if (_working) return;
        _working = true;
        Bar.CanClose = false;
        bool removeData = RemoveDataToggle.IsChecked == true;
        ProgressFill.BeginAnimation(WidthProperty, null);
        ProgressFill.Width = 0;
        RenderProgress(0, removeData);
        ShowPage(RemovingPage, step: 2);
        RemovingMark.SetSpeed(3.5); // the installer's Installing speed

        bool ok;
        try
        {
            ok = await Task.Run(() => Uninstaller.Run(_dir, removeData,
                f => Dispatcher.BeginInvoke(new Action(() => RenderProgress(f, removeData)))));
        }
        catch (Exception ex)
        {
            SetupLog.Write("uninstall failed: " + ex);
            ok = false;
        }

        if (ok) await Task.Delay(450); // let the bar land before the page changes, like the installer
        _working = false;
        Bar.CanClose = true;
        RemovingMark.SetSpeed(1);
        if (ok)
        {
            DoneBody.Text = DoneBodyFor(removedData: removeData);
            ShowPage(DonePage, step: 3);
            DoneCloseButton.IsDefault = true;
        }
        else
        {
            ProblemBody.Text = ProblemBodyFor(_dir);
            ShowPage(ProblemPage, step: 3);
            StepRail.Visibility = Visibility.Hidden;
            ProblemCloseButton.IsDefault = true;
        }
    }

    private static string DoneBodyFor(bool removedData) => removedData
        ? "Everything is gone, including your Origin data. Thanks for playing."
        : "Your accounts, settings and worlds were kept. Reinstall any time to pick up where you left off.";

    private static string ProblemBodyFor(string dir) =>
        $"Some files were in use and couldn't be deleted (Minecraft may still be running). You can delete them yourself: {dir}";

    // Same bar + status line + percent as the installer's; the wording follows what Uninstaller.Run is doing.
    private void RenderProgress(double f, bool removeData)
    {
        f = Math.Max(0, Math.Min(1, f));
        PageMotion.Width(ProgressFill, ProgressTrack.ActualWidth > 0 ? ProgressTrack.ActualWidth * f : 380 * f);
        PercentText.Text = $"{f * 100:0}%";
        StageText.Text = f < 0.2 ? "Closing Origin"
            : f < 0.4 ? "Removing shortcuts"
            : f < 0.75 ? "Removing Origin files"
            : f < 1 ? (removeData ? "Removing your Origin data" : "Finishing up")
            : "Done";
    }

    private void ShowPage(FrameworkElement page, int step)
    {
        StepRail.Visibility = Visibility.Visible;
        PageMotion.Show(page, _pages);
        SetStep(step);
    }

    // Same as SetupWindow.SetStep: lit = current, green = passed, dim = ahead.
    private void SetStep(int step)
    {
        var markers = new[] { Step1Marker, Step2Marker, Step3Marker };
        var labels = new[] { Step1Label, Step2Label, Step3Label };
        for (int i = 0; i < markers.Length; i++)
        {
            int n = i + 1;
            markers[i].Background = (Brush)FindResource(n == step ? "Brush.Accent" : n < step ? "Brush.Success" : "Brush.SoftStroke");
            labels[i].Foreground = (Brush)FindResource(n == step ? "Brush.Text" : "Brush.TextMuted");
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && !_working) Close();
    }

    private void Root_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed && e.OriginalSource == sender)
            DragMove();
    }

    private void Window_Closing(object sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_working) e.Cancel = true; // a half-removed install is worse than waiting a second
    }

    // ---------- CI screenshot tour (RenderTour.cs) ----------

    internal void TourRemoving(double fraction, bool removeData)
    {
        ShowPage(RemovingPage, step: 2);
        RenderProgress(fraction, removeData);
    }

    internal void TourDone(bool keptData)
    {
        DoneBody.Text = DoneBodyFor(removedData: !keptData);
        ShowPage(DonePage, step: 3);
    }

    internal void TourProblem()
    {
        ProblemBody.Text = ProblemBodyFor(_dir);
        ShowPage(ProblemPage, step: 3);
        StepRail.Visibility = Visibility.Hidden;
    }
}
