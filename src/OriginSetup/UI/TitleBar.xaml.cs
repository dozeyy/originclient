using System.Windows;
using System.Windows.Controls;

namespace OriginSetup.UI;

public partial class TitleBar : UserControl
{
    public TitleBar()
    {
        InitializeComponent();
    }

    /// <summary>The small label after the wordmark: "SETUP" or "UNINSTALL".</summary>
    public string Mode
    {
        get => ModeText.Text;
        set => ModeText.Text = value;
    }

    /// <summary>Close is disabled while files are being moved into place.</summary>
    public bool CanClose
    {
        get => CloseButton.IsEnabled;
        set => CloseButton.IsEnabled = value;
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) =>
        Window.GetWindow(this)!.WindowState = WindowState.Minimized;

    private void Close_Click(object sender, RoutedEventArgs e) => Window.GetWindow(this)!.Close();
}
