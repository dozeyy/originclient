namespace OriginLauncher.App.Core.Import.Launcher;

/// <summary>
/// True exactly while an import is writing to the player's game folders. Home reads it to keep Play and
/// Join disabled for that time (a launch would race the import); it re-enables them when it ends.
/// </summary>
public static class ImportRunState
{
    private static volatile bool _applying;

    public static bool IsApplying => _applying;

    /// <summary>Raised on whichever thread changed it; listeners marshal to the UI themselves.</summary>
    public static event Action? Changed;

    public static void Begin() => Set(true);

    public static void End() => Set(false);

    private static void Set(bool value)
    {
        if (_applying == value) return;
        _applying = value;
        Changed?.Invoke();
    }
}
