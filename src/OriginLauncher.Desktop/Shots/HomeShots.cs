using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// Home and the top bar, named like the Windows set (UI/RenderShots.cs) so the two sheets line up.
public static class HomeShots
{
    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        yield return new Shot("01-home", _ => { });
        yield return new Shot("01b-home-ready", _ => { }, Seed: () => Demo.SignIn());
        yield return new Shot("01c-home-play-hover", w => w.Home.PlayButton.ForceHover = true, Seed: () => Demo.SignIn());
        yield return new Shot("01d-home-my-mods-off", _ => { }, Seed: () =>
        {
            Demo.SignIn();
            App.Core.SettingsStore.Update(s => s.PlayWithExternalMods = false);
        });
        yield return new Shot("01f-home-version-hover", w => w.Home.VersionCard.ForceHover = true);
        yield return new Shot("01g-home-1.21.1", _ => { }, Seed: () => { Demo.SignIn(); Demo.Version("1.21.1"); });
        yield return new Shot("01h-home-1.8.9", _ => { }, Seed: () => { Demo.SignIn(); Demo.Version("1.8.9"); });
        yield return new Shot("11-home-profile", _ => { }, Seed: () => { Demo.SignIn(); Demo.Profiles(); });
        yield return new Shot("15-topbar-960", _ => { }, 960, 600, Seed: () => Demo.SignIn());
        yield return new Shot("15b-topbar-1044-update", _ => { }, 1044, 740);
        yield return new Shot("15c-topbar-1440", _ => { }, 1440, 900, Seed: () => Demo.SignIn("A_Very_Long_Gamertag1"));
        yield return new Shot("15d-topbar-no-update", _ => { }, Seed: () => { Demo.SignIn(); Demo.NoUpdate(); });
        yield return new Shot("29-min-home", _ => { }, 960, 600, Seed: () => { Demo.SignIn(); Demo.Profiles(); });
        yield return new Shot("99-dpi-home-1920x1080", _ => { }, 1920, 1080, Seed: () => Demo.SignIn());
    }
}
