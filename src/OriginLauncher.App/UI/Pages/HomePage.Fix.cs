using System.Windows;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.App.UI.Controls;

namespace OriginLauncher.App.UI.Pages;

// Phase 5.3: the Fix Compatibility prompt over a launch Play refused (LaunchPreflight). Kept in its own file so the
// launch method only hands the refusal over. The prompt shows the refused mods at once; the plan (and any Modrinth
// lookup, mod ids only) runs off the UI thread; nothing is written until the player presses Fix, and then only a
// COPY is made (Core/Components/FixCompatibility -> Core/Compat/RepairExecutor.Copy). The status line underneath
// keeps the refusal, so Play there still means "start anyway" (LaunchOverride).
public partial class HomePage
{
    private const string FixFoot = "Your original stays exactly as it is: the fix is made in a copy, which Play then starts.";

    // True while a fixed copy is being made: Play/Join are off and no second prompt opens.
    private bool _fixRunning;

    private async Task OfferFixAsync(IncompatibleModsException refused, string version, ModProfile? profile)
    {
        if (_fixRunning) return;
        try
        {
            var choice = OpenFixPrompt(refused.Names, version);

            var store = VersionManager.SharedComponents;
            var source = new FixSource(version, profile, OriginPaths.ModsDir(version),
                profile == null ? null : OriginPaths.ProfileModsDir(version, profile.Id),
                ModManager.IsManaged, ModManager.IsOriginProvisioned, ModManager.IsOriginsCopy);
            FixProposal? proposal = null;
            using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20)))
            {
                try
                {
                    proposal = await Task.Run(() => FixCompatibility.PlanAsync(source, store, store.Http, cts.Token), cts.Token);
                }
                catch (OperationCanceledException)
                {
                    // took too long (a slow network): the prompt says there is no fix it could prepare
                }
            }

            ShowFixPlan(proposal, profile);

            switch (await choice)
            {
                case FixChoice.LaunchAnyway:
                    await LaunchAsync(null, LaunchRoute.Play); // the refusal is still on the line: Play's own override
                    break;
                case FixChoice.Fix when proposal is { CanFix: true }:
                    await ApplyFixAsync(proposal);
                    break;
            }
        }
        catch (Exception ex)
        {
            // The prompt is a helper over a refusal the status line already explains: it never takes Home down.
            System.Diagnostics.Debug.WriteLine($"[HomePage] Fix Compatibility failed: {ex}");
            FixPrompt.Dismiss();
        }
    }

    // The prompt's first state: the refused mods by name (render shots call this too, CompatShots).
    private Task<FixChoice> OpenFixPrompt(IReadOnlyList<string> refusedNames, string version)
    {
        var names = refusedNames.Count <= 3
            ? string.Join(", ", refusedNames)
            : string.Join(", ", refusedNames.Take(3)) + $" and {refusedNames.Count - 3} more";
        return FixPrompt.ShowAsync(
            $"{(refusedNames.Count == 1 ? "A mod" : "Some mods")} won't start on {version}",
            names + " would stop the game. The check reads each mod's own description, which can be wrong.");
    }

    // The prompt's second state: what the fix would change, or plainly why there is none.
    private void ShowFixPlan(FixProposal? proposal, ModProfile? profile)
    {
        if (proposal == null)
            FixPrompt.ShowPlan("No fix is ready", Array.Empty<string>(),
                new[] { "Origin couldn't prepare a fix just now. Launch anyway tries the mods as they are." }, "", canFix: false);
        else if (proposal.CanFix)
            FixPrompt.ShowPlan(
                profile == null ? "Fix Compatibility makes a fixed profile from your mods that will:" : $"Fix Compatibility makes a copy of \"{profile.Name}\" that will:",
                proposal.Changes, proposal.NoFix, FixFoot, canFix: true);
        else
            FixPrompt.ShowPlan("Why there is no fix", Array.Empty<string>(), proposal.NoFix, "", canFix: false);
    }

    private async Task ApplyFixAsync(FixProposal proposal)
    {
        if (_fixRunning) return;
        // Play (and Join) stay off while the copy is made (RefreshPlayButton), so no second refusal can start a second
        // fix; FixCompatibility.Apply refuses one anyway.
        _fixRunning = true;
        RefreshPlayButton();
        RepairCopyResult result;
        try
        {
            SetStatus("Making a fixed copy...", StatusTone.Neutral);
            var store = VersionManager.SharedComponents;
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(2));
            result = await Task.Run(() => FixCompatibility.Apply(
                proposal, OriginPaths.ProfilesDir(proposal.Source.Version), OriginPaths.RepairJournals, SettingsStore.Update,
                new ModrinthDependencyFetcher(store.Http, cts.Token),
                id => SettingsStore.Load().ModProfiles.Any(p => p.Id == id),
                OriginPaths.ProfilesDir));
        }
        finally
        {
            _fixRunning = false;
            RefreshPlayButton();
        }
        if (!result.Ok)
        {
            SetStatus(result.Error!, StatusTone.Error);
            return;
        }
        RefreshProfile();
        SetStatus($"Made \"{result.ProfileName}\". Starting it...", StatusTone.Success);
        await LaunchAsync(null, LaunchRoute.Play);
    }
}
