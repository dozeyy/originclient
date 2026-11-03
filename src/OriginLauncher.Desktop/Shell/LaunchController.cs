using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.Desktop.Pages;

namespace OriginLauncher.Desktop.Shell;

// Play: sign-in refresh, install, launch, the launch cover, the running game, crash reports.
// One game per account; the newest launch action cancels only the same account's in-flight one
// (Core AccountLaunchBook). STUB: the launch port replaces this file.
public sealed class LaunchController
{
    private readonly MainWindow _window;
    private readonly HomePage _home;

    public LaunchController(MainWindow window, HomePage home)
    {
        _window = window;
        _home = home;
    }

    public AccountLaunchBook Book { get; } = new();

    /// <summary>Play is held off (an import or a compatibility fix is running).</summary>
    public bool Blocked { get; set; }

    public event Action? Changed;

    public Task PlayOrFocusAsync(string version) => PlayAsync(version, null);

    public Task PlayAsync(string version, string? server)
    {
        _window.Toast("Launching is not wired yet");
        Changed?.Invoke();
        _ = _home;
        return Task.CompletedTask;
    }
}
