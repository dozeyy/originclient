namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// What closing the launcher window does while an import is writing to the game folders: the close is held
/// back (the panel asks "Cancel the import?" instead), and once the import's result is back the window closes
/// by itself. Pure state, no WPF: MainWindow feeds it Closing and the ImportRunState change.
/// </summary>
public sealed class ImportCloseGuard
{
    private bool _closeRequested;

    /// <summary>The player tried to close while an import was running; the window closes when it ends.</summary>
    public bool CloseRequested => _closeRequested;

    /// <summary>
    /// MainWindow.OnClosing: true = set e.Cancel and show the panel's cancel confirm. False = let the close go
    /// (and forget any earlier request: the player is closing now).
    /// </summary>
    public bool ShouldHoldClose(bool importApplying)
    {
        _closeRequested = importApplying;
        return importApplying;
    }

    /// <summary>The import ended (or recovery did): true exactly once when a held close should now go through.</summary>
    public bool ShouldCloseNow(bool importApplying)
    {
        if (importApplying || !_closeRequested) return false;
        _closeRequested = false;
        return true;
    }

    /// <summary>The player changed their mind (the panel was closed another way, or the confirm was answered "keep").</summary>
    public void Forget() => _closeRequested = false;
}
