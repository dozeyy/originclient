using System.IO;
using System.Windows.Input;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.App.UI.Pages;
using Xunit;

namespace OriginLauncher.UiTests;

/// <summary>
/// The two-step version selector, driven the way a player drives it: through the real overlay's key handler and click
/// entry points, over install states built on disk in a temp folder (SelectorFakeInstalls), against a real HomePage so the
/// pick goes through the same SettingsStore.Update path as the app. Runs inside CrashWindowFallbackTests' STA body (one
/// Application per process); the scratch root and Motion.Off are set there before anything resolves OriginPaths.
/// Controls are constructed, never shown. The selector has no Play or Install of its own (Will, 2026-10-25): step 2 is
/// one dropdown, and picking a release in it is the whole job.
/// </summary>
internal static class VersionSelectorUiTests
{
    internal static void Run(string scratch)
    {
        var states = new Dictionary<string, InstallState>
        {
            ["1.21.1"] = InstallState.Installed,
            ["1.21.2"] = InstallState.UpdateReady,
            ["1.21"] = InstallState.Partial,
            ["1.8.9"] = InstallState.Partial,
        };
        var installs = SelectorFakeInstalls.Build(Path.Combine(scratch, "selector-installs"), states);
        installs.RefreshAllAsync().GetAwaiter().GetResult();

        Wording(installs);
        Keyboard(installs);
        Persistence(installs);
    }

    // What each card says and which releases wear the installed dot, off the production classifier's answers.
    private static void Wording(InstalledVersions installs)
    {
        var sheet = new VersionSelectOverlay { Installs = installs };
        sheet.Show("1.21.1");

        Assert.Equal("2 of 10 installed", sheet.BadgeTextOf("1.21")); // Installed + UpdateReady; the Partial one does not count
        Assert.Equal("Not installed", sheet.BadgeTextOf("1.20"));
        Assert.Equal("Finish install", sheet.BadgeTextOf("1.8")); // a single release shows its own state
        Assert.Equal("Not installed", sheet.BadgeTextOf("1.16"));

        sheet.ChooseFamily(1); // 1.21
        Assert.True(sheet.PanelShown); // the card grew into the panel
        var items = sheet.DropdownItems.ToDictionary(i => i.Version, i => i.Dot);
        Assert.Equal(VersionCatalog.Groups[1].Versions, sheet.DropdownItems.Select(i => i.Version)); // newest first, all of them
        Assert.True(items["1.21.1"]);   // installed
        Assert.True(items["1.21.2"]);   // an update waiting still counts as on disk
        Assert.False(items["1.21"]);    // half-installed: no dot, and no words about it
        Assert.False(items["1.21.11"]); // not installed: nothing at all, never "download"
        Assert.Null(sheet.DropdownChoice); // "Choose a version": nothing preselected, not even the saved one (Will, 2026-10-26)
        sheet.Dismiss();
    }

    // Arrows, Enter, Esc and back, on a bare overlay so the events can be observed without launching anything.
    private static void Keyboard(InstalledVersions installs)
    {
        var picked = new List<string>();
        var sheet = new VersionSelectOverlay { Installs = installs };
        sheet.VersionSelected += picked.Add;

        // Step 1: arrows only move; nothing is picked.
        sheet.Show("1.21.1");
        Assert.Equal(SelectorStep.Families, sheet.Nav.Step);
        Assert.Equal("1.21", sheet.Nav.Family.Key);
        Assert.True(sheet.HandleKey(Key.Right, null));
        Assert.Equal("1.20", sheet.Nav.Family.Key);
        Assert.True(sheet.HandleKey(Key.Down, null)); // one row down = four cards on
        Assert.Equal("1.16", sheet.Nav.Family.Key);
        Assert.True(sheet.HandleKey(Key.Left, null));
        Assert.Equal("1.17", sheet.Nav.Family.Key);
        Assert.Empty(picked);

        // Back up to 1.21; Enter expands it into the panel with the dropdown on "Choose a version".
        Assert.True(sheet.HandleKey(Key.Up, null));
        Assert.Equal("1.21", sheet.Nav.Family.Key);
        Assert.True(sheet.HandleKey(Key.Enter, null));
        Assert.Equal(SelectorStep.Releases, sheet.Nav.Step);
        Assert.True(sheet.PanelShown);
        Assert.Null(sheet.Nav.Release); // nothing chosen, even in the saved version's family
        Assert.Null(sheet.DropdownChoice);
        Assert.Empty(picked); // opening a family picks nothing

        // Step 2: Down / Up on the closed dropdown move the pick and pick as they go; Down from "Choose a version"
        // takes the newest.
        var releases = VersionCatalog.Groups[1].Versions;
        Assert.True(sheet.HandleKey(Key.Down, null));
        Assert.True(sheet.HandleKey(Key.Down, null));
        Assert.True(sheet.HandleKey(Key.Up, null));
        Assert.Equal([releases[0], releases[1], releases[0]], picked);
        Assert.Equal(releases[0], sheet.DropdownChoice);

        // Enter asks the dropdown to open its list (a ComboBox only opens once it is on screen, so the list itself is
        // checked in the render shots and DropdownKeyboardRegression): it picks, launches and installs nothing.
        Assert.True(sheet.HandleKey(Key.Enter, null));
        Assert.Equal([releases[0], releases[1], releases[0]], picked);
        Assert.True(sheet.IsOpen);

        // Back ("All versions") shrinks the panel into its card: step 1, still on the same family; the sheet stays open.
        sheet.Dismiss();
        sheet.Show("1.21.2");
        sheet.HandleKey(Key.Enter, null);
        Assert.True(sheet.HandleKey(Key.Left, null));
        Assert.Equal(SelectorStep.Families, sheet.Nav.Step);
        Assert.Equal("1.21", sheet.Nav.Family.Key);
        Assert.False(sheet.PanelShown);
        Assert.True(sheet.IsOpen);

        // Picking in the dropdown with the mouse is the whole job: picked, the panel stays until Esc or a click off it.
        picked.Clear();
        Assert.True(sheet.HandleKey(Key.Enter, null));
        sheet.ChooseRelease(0);
        Assert.Equal([VersionCatalog.Groups[1].Versions[0]], picked);
        Assert.True(sheet.IsOpen);
        Assert.True(sheet.HandleKey(Key.Escape, null));
        Assert.False(sheet.IsOpen);

        // Esc on step 2 closes the whole sheet, Esc on step 1 closes it too, and a keypress that means nothing is not taken.
        sheet.Show("1.20.4");
        sheet.HandleKey(Key.Enter, null);
        Assert.True(sheet.HandleKey(Key.Escape, null));
        Assert.False(sheet.IsOpen);
        sheet.Show("1.20.4");
        Assert.True(sheet.HandleKey(Key.Escape, null));
        Assert.False(sheet.IsOpen);
        sheet.Show("1.20.4");
        Assert.False(sheet.HandleKey(Key.F5, null));
        Assert.True(sheet.IsOpen);

        // A single-release family has no step 2: Enter picks it and closes.
        picked.Clear();
        sheet.Show("1.21.1");
        sheet.HandleKey(Key.End, null);
        Assert.Equal("1.8", sheet.Nav.Family.Key);
        Assert.True(sheet.HandleKey(Key.Enter, null));
        Assert.Equal(["1.8.9"], picked);
        Assert.False(sheet.IsOpen);

        // Enter on a focused button presses that button (Close, All versions): the sheet leaves it alone.
        sheet.Show("1.21.1");
        sheet.HandleKey(Key.Enter, null);
        Assert.False(sheet.HandleKey(Key.Enter, new System.Windows.Controls.Button()));
        Assert.False(sheet.DropdownOpen);
        sheet.Dismiss();
    }

    // The pick lands in settings.json through SettingsStore.Update, and only a pick does.
    private static void Persistence(InstalledVersions installs)
    {
        Assert.True(OriginPaths.IsScratchRoot, "the selector test must never write the real settings");

        SettingsStore.Update(s => s.SelectedVersion = "1.21.1");
        var home = new HomePage();
        home.VersionOverlay.Installs = installs;

        // Browsing and Esc change nothing.
        home.VersionOverlay.Show("1.21.1");
        home.VersionOverlay.HandleKey(Key.Right, null);
        home.VersionOverlay.HandleKey(Key.Right, null);
        home.VersionOverlay.HandleKey(Key.Enter, null);
        home.VersionOverlay.HandleKey(Key.Escape, null);
        Assert.Equal("1.21.1", SettingsStore.Load().SelectedVersion);

        // A release in the dropdown moves the saved version, and only that setting.
        var before = SettingsStore.Load();
        home.VersionOverlay.Show("1.21.1");
        home.VersionOverlay.HandleKey(Key.Enter, null);
        home.VersionOverlay.HandleKey(Key.Down, null);
        var after = SettingsStore.Load();
        Assert.Equal(VersionCatalog.Groups[1].Versions[0], after.SelectedVersion); // Down from "Choose a version": the newest
        Assert.Equal(before.RamManual, after.RamManual);
        Assert.Equal(before.JvmArgsByVersion.Count, after.JvmArgsByVersion.Count);

        // A mouse pick takes the same road.
        home.VersionOverlay.ChooseRelease(2); // 1.21.8
        Assert.Equal("1.21.8", SettingsStore.Load().SelectedVersion);

        // A one-release family: the card picks and the sheet closes.
        home.VersionOverlay.ChooseFamily(7);
        Assert.Equal("1.8.9", SettingsStore.Load().SelectedVersion);
        Assert.False(home.VersionOverlay.IsOpen);
    }
}
