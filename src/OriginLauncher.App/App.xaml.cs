using System.Windows;
using OriginLauncher.App.Core;

namespace OriginLauncher.App;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        OriginPaths.EnsureScaffold();
    }
}
