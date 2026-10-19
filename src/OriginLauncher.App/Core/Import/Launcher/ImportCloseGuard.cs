namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>Why a window close is being held back (or not).</summary>
public enum CloseHold
{
    /// <summary>Nothing is writing: the close goes through.</summary>
    None,

    /// <summary>The player's import is applying: ask "Cancel the import?" and close when its result is back.</summary>
    ImportRunning,

    /// <summary>Only the startup recovery is running (no panel): tell the player, the close goes through when it ends.</summary>
    RecoveryRunning,
}

/// <summary>
/// What the import panel (or its drawer replacement) gives the main window so closing the launcher mid-import can
/// ask the player and learn the ANSWER. MainWindow checks the open panel for this interface; a panel that does
/// not implement it is treated as "answer unknown" and the window never closes on its own afterwards.
/// </summary>
public interface IImportCloseHost
{
    /// <summary>True while this panel's import is writing.</summary>
    bool IsApplying { get; }

    /// <summary>
    /// Shows "Cancel the import?" and completes with the answer: true = the player chose to cancel (the import
    /// rolls back, then the held close goes through), false = keep importing (the close is forgotten).
    /// </summary>
    Task<bool> ConfirmCancelAsync();
}

/// <summary>
/// What closing the launcher window does while an import writes to the game folders: the close is held
/// back (the panel asks "Cancel the import?" instead), and once the import's result is back the window closes
/// by itself - but only if the player did not answer "keep importing". Pure state, no WPF: MainWindow feeds it
/// Closing, the confirm's answer and the ImportRunState change.
/// </summary>
public sealed class ImportCloseGuard
{
    private bool _closeRequested;

    /// <summary>The player tried to close while an import was running; the window closes when it ends.</summary>
    public bool CloseRequested => _closeRequested;

    /// <summary>
    /// MainWindow.OnClosing. Anything but <see cref="CloseHold.None"/> = set e.Cancel (and, for ImportRunning, ask the
    /// panel; for RecoveryRunning, show the toast). None lets the close go and forgets any earlier request.
    /// </summary>
    public CloseHold OnClosing(bool importRunning, bool panelImporting)
    {
        _closeRequested = importRunning;
        return !importRunning ? CloseHold.None : panelImporting ? CloseHold.ImportRunning : CloseHold.RecoveryRunning;
    }

    /// <summary>The player answered the cancel confirm: "keep importing" forgets the held close.</summary>
    public void OnConfirmAnswered(bool cancelImport)
    {
        if (!cancelImport) _closeRequested = false;
    }

    /// <summary>The import ended (or recovery did): true exactly once when a held close should now go through.</summary>
    public bool ShouldCloseNow(bool importRunning)
    {
        if (importRunning || !_closeRequested) return false;
        _closeRequested = false;
        return true;
    }

    /// <summary>The player changed their mind some other way, or the panel cannot report an answer.</summary>
    public void Forget() => _closeRequested = false;
}
