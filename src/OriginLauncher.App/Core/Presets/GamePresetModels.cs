namespace OriginLauncher.App.Core.Presets;

// The 1.8.9 performance presets (Phase 6, U6.2). Pure logic: no WPF, no OriginPaths, every folder a
// parameter, so the tests only ever touch temp folders. The Settings UI (later) calls GamePresetWriter.

/// <summary>The three bundles a player can pick. Anything else the files hold is shown as "Custom".</summary>
public enum GamePresetKind
{
    Competitive,
    Balanced,
    Quality,
}

/// <summary>The three files a preset can touch, all inside one account's game folder.</summary>
public enum GamePresetFile
{
    /// <summary>options.txt (vanilla keys).</summary>
    Options,

    /// <summary>optionsof.txt (OptiFine M5 keys, every one verified: tools/bench/optifine-keys-189.txt).</summary>
    OptiFine,

    /// <summary>config/originclient-mods.json (Origin's own toggles: mods.&lt;modId&gt;.&lt;key&gt;).</summary>
    Origin,
}

/// <summary>
/// One setting of a preset. For <see cref="GamePresetFile.Origin"/> the key is "modId.option" (the mod ids are the
/// 1.8.9 module's: "@general", "@performance", "menublur", "particles").
/// </summary>
/// <param name="Default">Origin only: what the mod uses while the key is absent from its config (the schema
/// default in Mods.java). A key that already equals the default is not written; null for the other files.</param>
public sealed record GamePresetEntry(GamePresetFile File, string Key, string Value, string? Default = null);

/// <summary>One line of a preview: this key goes from <see cref="Current"/> to <see cref="New"/>.</summary>
/// <param name="Current">The value in the file now; null when the key is not there (Origin keys show their default).</param>
public sealed record GamePresetChange(GamePresetFile File, string Key, string? Current, string New);

/// <summary>What Apply would do for one account (nothing is written to get this).</summary>
/// <param name="Changes">Only the keys that differ from the files now.</param>
/// <param name="Notes">Plain-English lines for the UI: Origin settings waiting for a first launch, a damaged file.</param>
/// <param name="Blocked">Non-null when Apply would refuse right now (game open, folder missing, damaged file): the reason.</param>
public sealed record GamePresetPreview(IReadOnlyList<GamePresetChange> Changes, IReadOnlyList<string> Notes, string? Blocked);

public enum GamePresetStatus
{
    /// <summary>Keys were written (Apply) or restored (Undo).</summary>
    Done,

    /// <summary>Apply found every key already at the preset's value; nothing was written.</summary>
    NothingToChange,

    /// <summary>The account's 1.8.9 game is starting or open; nothing was touched.</summary>
    Refused,

    /// <summary>Something else stopped it (missing folder, damaged file, disk error). Files are back as they were.</summary>
    Failed,
}

/// <summary>The outcome of Apply / Undo. <see cref="Message"/> is shown to the player as is.</summary>
public sealed record GamePresetResult(GamePresetStatus Status, string Message, IReadOnlyList<GamePresetChange> Changes)
{
    public bool Ok => Status is GamePresetStatus.Done or GamePresetStatus.NothingToChange;

    internal static GamePresetResult Fail(GamePresetStatus status, string message) =>
        new(status, message, Array.Empty<GamePresetChange>());
}

/// <summary>What Undo would restore: which preset was applied, and when (UTC).</summary>
public sealed record GamePresetUndoInfo(GamePresetKind Kind, DateTimeOffset AppliedUtc);
