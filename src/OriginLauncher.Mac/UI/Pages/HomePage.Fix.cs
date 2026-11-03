using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

// The Fix Compatibility prompt over a launch Play refused (twin of the WPF UI/Pages/HomePage.Fix.cs, the same Core
// calls and words). The plan runs off the UI thread; nothing is written until the player presses Fix, and then only a
// COPY is made (Core/Components/FixCompatibility). The status line keeps the refusal, so Play still means "start anyway".
public sealed partial class HomePage
{
    private const string FixFoot = "Your original stays exactly as it is: the fix is made in a copy, which Play then starts.";

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
                    // too slow (a slow network): the prompt says there is no fix it could prepare
                }
            }

            ShowFixPlan(proposal, profile);

            switch (await choice)
            {
                case FixChoice.LaunchAnyway:
                    await LaunchAsync(null, LaunchRoute.Play);
                    break;
                case FixChoice.Fix when proposal is { CanFix: true }:
                    await ApplyFixAsync(proposal);
                    break;
            }
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[HomePage] Fix Compatibility failed: {ex}");
            FixPrompt.Dismiss();
        }
    }

    /// <summary>
    /// The import report's "Fix Compatibility" on a profile it says can't start (ImportHooks.FixCompatibility, wired by
    /// MainWindow, which has already selected that profile and shown Home). The same check Play runs decides, off the UI
    /// thread, and the same prompt opens: nothing is written until the player presses Fix, and then only a copy is made.
    /// Nothing to fix any more (the check now passes): the status line says so and nothing starts.
    /// </summary>
    internal async Task FixImportedProfileAsync(string version, string profileId)
    {
        if (_fixRunning) return;
        var profile = SettingsStore.Load().ModProfiles.FirstOrDefault(p => p.Id == profileId && p.Version == version);
        if (profile == null) return;
        LaunchVerdict verdict;
        try
        {
            verdict = await Task.Run(() => LaunchPreflight.Evaluate(version, OriginPaths.ModsDir(version),
                OriginPaths.ProfileModsDir(version, profile.Id), true, ModManager.IsManaged, ModManager.IsOriginProvisioned,
                confirmOrigin: ModManager.IsOriginsCopy));
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            verdict = LaunchVerdict.Clear; // fail-soft, as the launch check is: Play checks again
        }
        if (verdict.Blocking.Count == 0)
        {
            SetStatus($"\"{profile.Name}\" passes the start check now. Press Play to start it.", StatusTone.Neutral);
            return;
        }
        await OfferFixAsync(new IncompatibleModsException(version, verdict.BlockingNames, verdict.Blocking, verdict.SetKey),
            version, profile);
    }

    private Task<FixChoice> OpenFixPrompt(IReadOnlyList<string> refusedNames, string version)
    {
        var names = refusedNames.Count <= 3
            ? string.Join(", ", refusedNames)
            : string.Join(", ", refusedNames.Take(3)) + $" and {refusedNames.Count - 3} more";
        return FixPrompt.ShowAsync(
            $"{(refusedNames.Count == 1 ? "A mod" : "Some mods")} won't start on {version}",
            names + " would stop the game. The check reads each mod's own description, which can be wrong.");
    }

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

    // ---- Render shots only (CompatShots twins) ----
    internal void TourOpenFix(IReadOnlyList<string> names, string version) => _ = OpenFixPrompt(names, version);

    internal void TourShowFixPlan(FixProposal? proposal, ModProfile? profile) => ShowFixPlan(proposal, profile);

    internal void TourCloseFix() => FixPrompt.Dismiss();
}
