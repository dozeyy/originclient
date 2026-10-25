using System.Reflection;
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

        // Leave the page as the tour expects it.
        window.TourShowProfiles(demoProfiles, demoCount, openForm: false);
        RenderShots.Pump(300);
    }

    private static object Get(object o, string name) =>
        o.GetType().GetProperty(name, Any)!.GetValue(o)!;
}
