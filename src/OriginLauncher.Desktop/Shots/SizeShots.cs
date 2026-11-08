using OriginLauncher.Desktop.Overlays;
using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

// The Windows set's window-size twins (UI/RenderShots.cs 15-15f, UI/SelectorShots.cs p3sel-<size>-*) under the same
// names, so narrow and wide windows on Linux are in the sheet and in CI's "Windows shots with no Linux twin" list
// (PAR3-8): the top bar at 960 / 1044 / 1180 / 1280 / 1440 wide with and without the update chip, the Home numeral at the
// three reference sizes, and the version picker's steps at 960x600 and 1920x1080 (1180x740 is in LaunchShots).
public static class SizeShots
{
    [ShotSet]
    public static IEnumerable<Shot> All()
    {
        // The Windows names: 15 = 1044 wide (the CI desktop's cap there), 15c/15d = the 960 minimum.
        yield return new Shot("15-topbar-1044", _ => Demo.NoUpdate(), 1044, 740);
        yield return new Shot("15c-topbar-960", _ => Demo.NoUpdate(), 960, 600);
        yield return new Shot("15d-topbar-960-update", _ => { }, 960, 600);
        foreach (var (w, h) in new[] { (1440, 900), (1280, 800), (1180, 740) })
        {
            yield return new Shot($"15e-topbar-{w}x{h}", _ => Demo.NoUpdate(), w, h);
            yield return new Shot($"15e-topbar-{w}x{h}-update", _ => { }, w, h);
        }
        foreach (var (w, h) in new[] { (960, 600), (1180, 740), (1920, 1080) })
            yield return new Shot($"15f-home-numeral-{w}x{h}", _ => Demo.NoUpdate(), w, h, Seed: () => Demo.SignIn());

        foreach (var (w, h) in new[] { (960, 600), (1920, 1080) })
        {
            var p3 = $"p3sel-{w}x{h}-";
            yield return new Shot(p3 + "step1", win => Picker(win), w, h, Seed: Signed);
            yield return new Shot(p3 + "step1-focus", win => Picker(win).Cards[4].ForceFocus = true, w, h, Seed: Signed);
            foreach (var family in new[] { "1.21", "1.20", "1.19" })
                yield return new Shot(p3 + "step2-" + family, win => Picker(win).OpenFamily(family), w, h, Seed: Signed);
            yield return new Shot(p3 + "step2-1.21-focus", win =>
            {
                var p = Picker(win);
                p.OpenFamily("1.21");
                p.Releases.Trigger.ForceFocus = true;
            }, w, h, Seed: Signed);
        }
    }

    private static void Signed()
    {
        Demo.SignIn();
        Demo.Version("1.21.1");
    }

    // The picker over LaunchShots' made-up installs (the Windows SelectorShots set), as the 1180x740 p3sel shots use.
    private static VersionPicker Picker(MainWindow w) => LaunchShots.Picker(w, LaunchShots.Fake());
}
