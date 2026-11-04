using OriginLauncher.App.Core.GameFolders.Launcher;
using OriginLauncher.App.Core.Import.Bulk;

namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// Home's one-click transfer (ImportPrompt): scan, plan with the defaults, copy, in one go, through the same backend and
/// engine the Import page uses, so every rule (what qualifies, a profile per setup, rollback on failure, never twice)
/// is the engine's. No UI here: each launcher's Home draws the steps and the outcome (HomeImport has the words).
/// The work runs on the thread pool; the caller awaits it from the UI thread. While it runs ImportRunState is busy, so
/// Play and Join wait for it, the Import page refuses to open, and closing the window waits until it is done.
/// </summary>
public static class HomeImportRunner
{
    /// <param name="source">One launcher's setups, or null for every launcher's ("All of them").</param>
    public static async Task<HomeImportOutcome> RunAsync(IImportBackend backend, SourceKind? source,
        IProgress<HomeImportProgress>? progress, CancellationToken ct)
    {
        using var run = ImportRunState.Enter();
        var name = source is { } kind ? ImportPrompt.ShortName(kind) : null;
        progress?.Report(new HomeImportProgress(HomeImportStep.Checking, 0));
        BulkImportPlan plan;
        string? account;
        try
        {
            // The account the transfer is for, read once (off the UI thread: it opens the accounts file). The scan
            // leaves out what this account already brought over (ImportMemory) and the plan copies into this account's
            // folders, so an account switch while Origin is still checking must not mix the two: refused below.
            account = await Task.Run(() => AccountFolders.SelectedKey(), ct).ConfigureAwait(false);
            if (account == null) return HomeImport.Refused(SignInFirst);
            // A fresh scan: the backend plans from its own last scan, and anything brought over since the button was
            // offered is left out by it (ImportMemory), so pressing it twice never copies twice.
            await backend.ScanAsync(null, ct).ConfigureAwait(false);
            plan = await backend.PlanBulkAsync(ImportPrompt.Selection(source), ImportPrompt.Options(), null, null, ct).ConfigureAwait(false);
            if (!HomeImport.SameAccount(account, plan.Request?.AccountKey, await Task.Run(() => AccountFolders.SelectedKey(), ct).ConfigureAwait(false)))
                return HomeImport.Refused(HomeImport.AccountChanged);
        }
        catch (OperationCanceledException)
        {
            return HomeImport.Stopped;
        }
        catch (Exception ex)
        {
            return HomeImport.Refused(ImportErrors.Plain(ex));
        }
        if (plan.Blocked.Count > 0) return HomeImport.Refused(plan.Blocked[0]);
        if (plan.Groups.Count == 0) return HomeImport.NothingToCopy(plan.Skipped);

        // Relayed straight through (a Progress<T> made on the pool would post each report on its own, out of order);
        // the caller's own Progress<T> brings them to its UI thread.
        var copying = new Relay(b => progress?.Report(new HomeImportProgress(HomeImportStep.Copying, HomeImport.Fraction(b))));
        BulkImportResult result;
        try
        {
            // Not tied to ct up front: a Stop is the engine's to handle (it rolls back and reports Cancelled).
            result = await Task.Run(() => backend.ApplyBulkAsync(plan, copying, ct), CancellationToken.None).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return HomeImport.Stopped;
        }
        catch (Exception ex)
        {
            return HomeImport.Crashed(ImportErrors.Plain(ex));
        }
        return HomeImport.Outcome(result, name);
    }

    private const string SignInFirst = "Sign in first, then try again.";

    private sealed class Relay(Action<BulkProgress> report) : IProgress<BulkProgress>
    {
        public void Report(BulkProgress value) => report(value);
    }
}
