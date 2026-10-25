using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Models;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.UI.Pages;

namespace OriginLauncher.App.UI;

/// <summary>
/// Render mode only (called from RenderShots): the Mods and Profiles states the main tour does not reach, so every
/// state of both screens has a shot to judge: Mods (empty, a status note, a status error, the remove confirm, no
/// version chosen) and Profiles (empty, a status note, a status error, the delete confirm, the edit form, the form
/// with an error). Each calls the page's own Tour* hook; nothing is drawn by the harness and nothing reads or
/// writes the player's real settings or folders.
/// </summary>
internal static class ModsProfilesShots
{
    private const BindingFlags Any = BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public;

    /// <param name="prefix">"28" for the normal window, "29-min" for the smallest one.</param>
    internal static void Run(MainWindow window, string outDir, string prefix, ModEntry[] demoMods,
        LauncherSettings demoProfiles, Func<ModProfile, int> demoCount)
    {
        var mods = (ModsPage)Get(window, "ModsView");
        var profiles = (ProfilesPage)Get(window, "ProfilesView");

        // ---- Mods ----
        window.TourShowModsDemo(Array.Empty<ModEntry>(), 0);
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}a-mods-empty");

        window.TourShowModsDemo(demoMods, 0);
        mods.TourShowStatus("Adding 2 mods...", error: false);
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}b-mods-status-note");

        mods.TourShowStatus("Couldn't remove it. Close Minecraft first, then try again.", error: true);
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}c-mods-status-error");

        window.TourShowModsDemo(demoMods, 0);
        mods.TourConfirmRemove("litematica-fabric-1.21.1-0.19.50.jar");
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}d-mods-remove-confirm");

        mods.TourNoVersion();
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}e-mods-no-version");

        // ---- Profiles ----
        window.TourShowProfiles(new LauncherSettings(), demoCount, openForm: false);
        RenderShots.Pump(600);
        RenderShots.Snap(window, outDir, $"{prefix}f-profiles-empty");

        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        profiles.TourShowStatus("Created \"Survival SMP\". Add its mods on the Mods page.", error: false);
        RenderShots.Pump(600);
        RenderShots.Snap(window, outDir, $"{prefix}g-profiles-status-note");

        profiles.TourShowStatus("\"Survival SMP\" is being used by a game that's running. Close Minecraft first.", error: true);
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}h-profiles-status-error");

        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        profiles.TourAskDelete();
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}i-profiles-delete-confirm");

        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        profiles.TourEditForm();
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}j-profiles-form-edit");

        // The form's error: the real rule's own sentence for a name that is taken (never a made-up one).
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        var taken = demoProfiles.ModProfiles[0];
        var error = ProfileRules.ValidateName(taken.Name, taken.Version, demoProfiles.ModProfiles) ?? "That name is taken.";
        profiles.TourFormError(taken.Name, error);
        RenderShots.Pump(500);
        RenderShots.Snap(window, outDir, $"{prefix}k-profiles-form-error");

        // ---- Keyboard focus (appended to focus-checks.txt, which the Import shots start) ----
        var log = new System.Text.StringBuilder();
        window.Activate(); // the render window opens without activation; keyboard focus needs an active window
        RenderShots.Pump(200);

        window.TourShowModsDemo(demoMods, 0);
        RenderShots.Pump(500);
        FocusShot(window, outDir, $"{prefix}l-mods-focus-open-folder", mods.FindName("OpenFolderButton") as FrameworkElement, "mods: Open folder", log);
        FocusShot(window, outDir, $"{prefix}m-mods-focus-remove", FirstButton(mods.FindName("UserModsPanel") as DependencyObject), "mods: first row's button", log);

        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        RenderShots.Pump(600);
        var cards = profiles.FindName("RowsPanel") as DependencyObject;
        FocusShot(window, outDir, $"{prefix}n-profiles-focus-card", FirstButton(cards, "Use"), "profiles: a card's Use button", log);
        FocusShot(window, outDir, $"{prefix}o-profiles-focus-new", LastButton(cards), "profiles: New profile card", log);
        File.AppendAllText(Path.Combine(outDir, "focus-checks.txt"), log.ToString());

        // Leave the page as the tour expects it.
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        RenderShots.Pump(300);
    }

    // Focus the control, snap with its keyboard focus frame (ImportShots.FocusFrame draws the control's own
    // FocusVisualStyle when WPF's adorner is not there), then take the frame off again.
    private static void FocusShot(MainWindow window, string outDir, string name, FrameworkElement? target, string what, System.Text.StringBuilder log)
    {
        target?.Focus();
        RenderShots.Pump(250);
        var undo = ImportShots.FocusFrame(window, target, what, log);
        RenderShots.Snap(window, outDir, name, adorners: true);
        undo?.Invoke();
    }

    // The visible, enabled buttons under a root, in visual order. "Use" keeps those whose automation name starts with it.
    private static List<Button> Buttons(DependencyObject? root)
    {
        var found = new List<Button>();
        void Walk(DependencyObject node)
        {
            for (var i = 0; i < VisualTreeHelper.GetChildrenCount(node); i++)
            {
                var child = VisualTreeHelper.GetChild(node, i);
                if (child is Button { IsVisible: true, IsEnabled: true, Focusable: true } b) found.Add(b);
                Walk(child);
            }
        }
        if (root != null) Walk(root);
        return found;
    }

    private static Button? FirstButton(DependencyObject? root, string? namePrefix = null) =>
        Buttons(root).FirstOrDefault(b => namePrefix == null
            || (System.Windows.Automation.AutomationProperties.GetName(b) ?? "").StartsWith(namePrefix, StringComparison.Ordinal));

    private static Button? LastButton(DependencyObject? root) => Buttons(root).LastOrDefault();

    private static object Get(object o, string name) =>
        o.GetType().GetProperty(name, Any)!.GetValue(o)!;
}
