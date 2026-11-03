using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Accounts;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// The made-up data render runs draw (the scratch root only: OriginPaths.UseScratchRoot was called first, so
// nothing here can reach a real data folder). Every shot starts from Reset(): signed out, newest version, no
// profiles, an update waiting (the widest top bar, as the Windows shots show it).
public static class Demo
{
    public const string PlayerId = "069a79f444e94726a5befca90e38aaf5";
    public const string PlayerName = "OriginPlayer";

    public static void Reset()
    {
        AccountStore.Save([]);
        SettingsStore.Update(s =>
        {
            var fresh = new LauncherSettings();
            s.SelectedVersion = null;
            s.ModProfiles = [];
            s.SelectedProfileId = null;
            s.PlayWithExternalMods = true;
            s.OfflineTestMode = false;
            s.RamMb = fresh.RamMb;
            s.RamManual = false;
        });
        LauncherState.Reset();
        LauncherState.SetUpdate("1.0.99");
    }

    /// <summary>A signed-in account (no real token: the protected fields hold placeholders, never decrypted).</summary>
    public static void SignIn(string name = PlayerName, string id = PlayerId, bool alsoSecond = false)
    {
        var list = new List<StoredAccount>
        {
            new() { Id = id, Gamertag = name, IsSelected = true, ProtectedRefreshToken = "demo", LastUsedUtc = DateTimeOffset.UtcNow },
        };
        if (alsoSecond)
            list.Add(new StoredAccount
            {
                Id = "853c80ef3c3749fdaa49938b674adae6", Gamertag = "Kai_Builds", ProtectedRefreshToken = "demo",
                LastUsedUtc = DateTimeOffset.UtcNow.AddDays(-2),
            });
        AccountStore.Save(list);
        LauncherState.Reset();
    }

    public static void Version(string version) => SettingsStore.Update(s => s.SelectedVersion = version);

    public static void NoUpdate() => LauncherState.SetUpdate(null);

    /// <summary>The Windows shots' profiles (UI/RenderShots.cs): one with a server, selected.</summary>
    public static void Profiles(bool select = true) => SettingsStore.Update(s =>
    {
        s.ModProfiles =
        [
            new ModProfile { Id = "0a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Survival SMP", Version = "1.21.1", Icon = "Pickaxe", ServerName = "Survival SMP", ServerAddress = "play.example.net" },
            new ModProfile { Id = "1a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "PvP practice", Version = "1.8.9", Icon = "Sword", Tint = "Wine", ServerName = "Practice", ServerAddress = "pvp.example.org:25570" },
            new ModProfile { Id = "2a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Singleplayer shaders", Version = "1.20.1", Tint = "Plum", Icon = "Camera" },
            new ModProfile { Id = "3a1b2c3d4e5f60718293a4b5c6d7e8f9", Name = "Skyblock with a much longer profile name", Version = "1.20.1", Tint = "Moss", Icon = "Map" },
        ];
        s.SelectedProfileId = select ? "0a1b2c3d4e5f60718293a4b5c6d7e8f9" : null;
        if (select) s.SelectedVersion = "1.21.1";
    });
}
