using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.Desktop.Shell;

// The one place the views read launcher state from, so every screen shows the same thing.
// Settings are written ONLY through SettingsStore.Update(mutate) (here: Write), then re-read and broadcast;
// accounts only through AccountStore. All events are raised on the UI thread.
public static class LauncherState
{
    private static LauncherSettings? _settings;
    private static List<StoredAccount>? _accounts;

    /// <summary>The settings as last read from disk (a snapshot: never mutate it, call Write).</summary>
    public static LauncherSettings Settings => _settings ??= SettingsStore.Load();

    public static event Action? SettingsChanged;
    public static event Action? AccountsChanged;
    public static event Action? UpdateChanged;

    /// <summary>The single settings write path: one mutation, saved at once, then every view refreshes.</summary>
    public static void Write(Action<LauncherSettings> mutate)
    {
        SettingsStore.Update(mutate);
        ReloadSettings();
    }

    public static void ReloadSettings()
    {
        _settings = SettingsStore.Load();
        Raise(SettingsChanged);
    }

    public static IReadOnlyList<StoredAccount> Accounts => _accounts ??= SafeLoadAccounts();

    public static StoredAccount? Account => AccountStore.GetSelected(Accounts as List<StoredAccount> ?? Accounts.ToList());

    /// <summary>The selected account's folder key (InstanceLayout.KeyFor): "offline" when nobody is signed in.</summary>
    public static string AccountKey => App.Core.GameFolders.InstanceLayout.KeyFor(Account?.Id);

    public static void ReloadAccounts()
    {
        _accounts = SafeLoadAccounts();
        Raise(AccountsChanged);
    }

    private static List<StoredAccount> SafeLoadAccounts()
    {
        try { return AccountStore.Load(); }
        catch (Exception) { return []; } // a broken or locked accounts file must never stop the launcher opening
    }

    /// <summary>The version Home shows: the saved pick when it is still offered, else the newest.</summary>
    public static string SelectedVersion =>
        VersionCatalog.IsSupported(Settings.SelectedVersion) ? Settings.SelectedVersion! : VersionCatalog.DefaultVersion;

    /// <summary>A launcher update is downloaded and waiting (the top bar's Update chip).</summary>
    public static string? UpdateVersion { get; private set; }

    public static void SetUpdate(string? version)
    {
        UpdateVersion = version;
        Raise(UpdateChanged);
    }

    private static void Raise(Action? e)
    {
        if (e == null) return;
        if (Dispatcher.UIThread.CheckAccess()) e();
        else Dispatcher.UIThread.Post(e);
    }

    /// <summary>Forget cached state (render runs swap the made-up data between shots).</summary>
    public static void Reset()
    {
        _settings = null;
        _accounts = null;
    }
}
