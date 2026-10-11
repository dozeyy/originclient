using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using OriginSetup.Engine;

namespace OriginSetup.UI;

// Confirm -> Removing -> Done. "Keep Origin" is the default (Enter) and the
// data toggle starts OFF: uninstalling never deletes worlds unless asked.
public partial class UninstallWindow : Window
{
    private readonly string _dir;
    private bool _working;

    public UninstallWindow(string installDir)
    {
        InitializeComponent();
        _dir = installDir;
    }

    private void RemoveDataToggle_Changed(object sender, RoutedEventArgs e) =>
        UninstallButton.Content = RemoveDataToggle.IsChecked == true ? "Uninstall and delete data" : "Uninstall";

    private void Keep_Click(object sender, RoutedEventArgs e) => Close();

    private async void Uninstall_Click(object sender, RoutedEventArgs e)
    {
        _working = true;
        Bar.CanClose = false;
        bool removeData = RemoveDataToggle.IsChecked == true;
        PageMotion.Show(RemovingPage, ConfirmPage, RemovingPage, DonePage);
        RemovingMark.SetSpeed(3);

        bool ok;
        try
        {
            ok = await Task.Run(() => Uninstaller.Run(_dir, removeData,
                f => Dispatcher.BeginInvoke(new Action(() => PageMotion.Width(ProgressFill, ProgressTrack.ActualWidth * f)))));
        }
        catch (Exception ex)
        {
            SetupLog.Write("uninstall failed: " + ex);
            ok = false;
        }

        _working = false;
        Bar.CanClose = true;
        DoneTitle.Text = ok ? "Origin has been removed" : "Origin is mostly removed";
        DoneBody.Text = ok
            ? (removeData ? "Everything is gone, including your Origin data. Thanks for playing." : "Your accounts, settings and worlds were kept — reinstall any time to pick up where you left off.")
            : $"Some files were in use and couldn't be deleted (Minecraft may still be running). You can delete them yourself: {_dir}";
        PageMotion.Show(DonePage, ConfirmPage, RemovingPage, DonePage);
        DoneCloseButton.IsDefault = true;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

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

    internal void TourRemoving()
    {
        PageMotion.Show(RemovingPage, ConfirmPage, RemovingPage, DonePage);
        PageMotion.Width(ProgressFill, 320 * 0.6);
    }

    internal void TourDone(bool keptData)
    {
        DoneTitle.Text = "Origin has been removed";
        DoneBody.Text = keptData
            ? "Your accounts, settings and worlds were kept — reinstall any time to pick up where you left off."
            : "Everything is gone, including your Origin data. Thanks for playing.";
        PageMotion.Show(DonePage, ConfirmPage, RemovingPage, DonePage);
    }
}
