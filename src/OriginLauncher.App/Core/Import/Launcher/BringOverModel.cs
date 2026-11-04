namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>What Home's bring-over button shows (BringOverModel.Stage).</summary>
public enum BringOverStage
{
    /// <summary>Nothing to offer, or the player said no: not on screen.</summary>
    Hidden,

    /// <summary>"Bring over your Lunar setup" (one launcher) or "Bring over your Minecraft setups" (several).</summary>
    Offer,

    /// <summary>Several launchers: one row per launcher, "All of them", and the way to the full Import page.</summary>
    Choosing,

    /// <summary>The transfer runs: its line and progress, and Stop.</summary>
    Running,

    /// <summary>It came over: one plain line, and what didn't come over.</summary>
    Done,

    /// <summary>It didn't (stopped, refused, failed): nothing changed, and Try again.</summary>
    Failed,
}

/// <summary>What a press on the button asks the window to do.</summary>
public enum BringOverPress
{
    /// <summary>Nothing (not on screen, or already running).</summary>
    None,

    /// <summary>Exactly one launcher: run its transfer now (<see cref="BringOverModel.OneSource"/>).</summary>
    RunOne,

    /// <summary>Several launchers: the choice opened (or closed again).</summary>
    Choose,
}

/// <summary>
/// Home's one-click bring-over button as plain state (Will, 2026-11-04), shared by the Windows, Mac and Linux Homes so
/// they behave the same; each draws it in its own toolkit. The words come from ImportPrompt and HomeImport, the run
/// from HomeImportRunner. The window persists the answer (LauncherSettings.ImportPromptSeen, SettingsStore.Update only)
/// when <see cref="Dismiss"/> or <see cref="Finish"/> says so.
/// </summary>
public sealed class BringOverModel
{
    public BringOverStage Stage { get; private set; } = BringOverStage.Hidden;

    /// <summary>The launchers the offer is about (ImportPrompt.Sources), most setups first.</summary>
    public IReadOnlyList<ImportSourceChoice> Sources { get; private set; } = Array.Empty<ImportSourceChoice>();

    /// <summary>The button's words for the offer ("Bring over your Lunar setup").</summary>
    public string OfferLabel { get; private set; } = "";

    /// <summary>The launcher the running (or last) transfer brings; null = all of them.</summary>
    public SourceKind? Running { get; private set; }

    public HomeImportProgress? Progress { get; private set; }

    public HomeImportOutcome? Outcome { get; private set; }

    /// <summary>Import is refused right now (a game starting, an update): the offer stays out of sight meanwhile.</summary>
    public bool Refused { get; private set; }

    /// <summary>The single launcher a press transfers at once, else null.</summary>
    public SourceKind? OneSource => Sources.Count == 1 ? Sources[0].Kind : null;

    /// <summary>
    /// On screen. An offer (or the choice) hides while Import is refused and comes back after; a running transfer and its
    /// result always stay, so the player sees how it ended.
    /// </summary>
    public bool Visible => Stage switch
    {
        BringOverStage.Hidden => false,
        BringOverStage.Offer or BringOverStage.Choosing => !Refused,
        _ => true,
    };

    /// <summary>The one line the button shows now.</summary>
    public string Line => Stage switch
    {
        BringOverStage.Running => HomeImport.RunningLine(SourceName(Running), Progress),
        BringOverStage.Done or BringOverStage.Failed => Outcome?.Line ?? "",
        _ => OfferLabel,
    };

    /// <summary>The second line, under it: what didn't come over (Done / Failed), else null.</summary>
    public string? Detail => Stage is BringOverStage.Done or BringOverStage.Failed ? Outcome?.LeftOut : null;

    /// <summary>The progress rail's fraction while copying; null while still checking (no fake number: no rail yet).</summary>
    public double? Fraction => Stage == BringOverStage.Running && Progress is { Step: HomeImportStep.Copying } p ? Math.Clamp(p.Fraction, 0, 1) : null;

    /// <summary>The scan found these QUALIFYING setups (ImportPrompt.Qualifying): offer them. None hides the button.</summary>
    public void Offer(IReadOnlyCollection<DetectedInstance> qualifying)
    {
        if (Stage == BringOverStage.Running) return; // a late scan never replaces a transfer on screen
        Sources = ImportPrompt.Sources(qualifying);
        OfferLabel = ImportPrompt.Label(qualifying);
        Outcome = null;
        Progress = null;
        Stage = Sources.Count == 0 ? BringOverStage.Hidden : BringOverStage.Offer;
    }

    /// <summary>The button was pressed: one launcher runs at once; several open the choice (a second press closes it).</summary>
    public BringOverPress Press()
    {
        switch (Stage)
        {
            case BringOverStage.Offer when OneSource != null:
                return BringOverPress.RunOne;
            case BringOverStage.Offer:
                Stage = BringOverStage.Choosing;
                return BringOverPress.Choose;
            case BringOverStage.Choosing:
                Stage = BringOverStage.Offer;
                return BringOverPress.Choose;
            default:
                return BringOverPress.None;
        }
    }

    /// <summary>The transfer of <paramref name="source"/> (null = every launcher) starts. False when one already runs.</summary>
    public bool Start(SourceKind? source)
    {
        if (Stage == BringOverStage.Running) return false;
        Running = source;
        Progress = new HomeImportProgress(HomeImportStep.Checking, 0);
        Outcome = null;
        Stage = BringOverStage.Running;
        return true;
    }

    /// <summary>A progress report. The rail never goes backwards (a later step can count its own files from zero).</summary>
    public void Report(HomeImportProgress progress)
    {
        if (Stage != BringOverStage.Running) return;
        if (Progress is { Step: HomeImportStep.Copying } was && progress.Step == HomeImportStep.Copying)
            progress = progress with { Fraction = Math.Max(was.Fraction, progress.Fraction) };
        Progress = progress;
    }

    /// <summary>The transfer ended. True = the player went through with it: the window records the answer for good.</summary>
    public bool Finish(HomeImportOutcome outcome)
    {
        Outcome = outcome;
        Progress = null;
        Stage = outcome.Succeeded ? BringOverStage.Done : BringOverStage.Failed;
        return outcome.Succeeded;
    }

    /// <summary>Try again after a failed or stopped transfer: the same launcher (or all) once more. False when nothing to retry.</summary>
    public bool Retry(out SourceKind? source)
    {
        source = Running;
        return Stage == BringOverStage.Failed && Start(source);
    }

    /// <summary>
    /// The close button ("No thanks" on an offer, or closing a result): the button goes away. True = record the answer for
    /// good (every close but one during a run, which has no close). A finished transfer recorded it already; once more
    /// does no harm.
    /// </summary>
    public bool Dismiss()
    {
        if (Stage is BringOverStage.Hidden or BringOverStage.Running) return false;
        Stage = BringOverStage.Hidden;
        return true;
    }

    /// <summary>The Import page opened from anywhere: the offer has done its job (a running transfer or its result stays).</summary>
    public void HideOffer()
    {
        if (Stage is BringOverStage.Offer or BringOverStage.Choosing) Stage = BringOverStage.Hidden;
    }

    public void SetRefused(bool refused) => Refused = refused;

    private static string? SourceName(SourceKind? kind) => kind is { } k ? ImportPrompt.ShortName(k) : null;
}
