namespace OriginLauncher.App.Core.Models;

// Per-version custom JVM arguments (Settings -> JVM arguments). Stored per
// Minecraft version because the right flags genuinely differ by version: the
// Java level differs (8 / 16 / 17 / 21 / 25 across the lineup), and a flag that
// is valid on one JVM aborts startup on another, so one global box would be a
// footgun.
public sealed class VersionJvmOptions
{
    // Raw text exactly as typed, so the editor round-trips what the player
    // wrote (line breaks and all) instead of a normalized rewrite.
    public string Arguments { get; set; } = "";

    // false (default): these args are appended AFTER Origin's tuned preset, so
    // a flag repeated here wins (the JVM takes the last occurrence) without
    // losing the rest of the tuning.
    // true: Origin's preset is dropped entirely and only these args are passed.
    public bool ReplacePresets { get; set; }

    // An entry with no actual arguments and default behavior is indistinguishable
    // from having no entry at all — used to drop it from settings.json rather
    // than accumulate empty objects for every version the player clicks through.
    public bool IsEmpty => string.IsNullOrWhiteSpace(Arguments) && !ReplacePresets;
}
