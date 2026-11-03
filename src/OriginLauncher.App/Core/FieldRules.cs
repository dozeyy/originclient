namespace OriginLauncher.App.Core;

/// <summary>
/// What is wrong with a value typed into a Settings field, in words a player can act on, or null when it is fine.
/// Pure (no WPF, no file system, and no Path.* calls, so it answers the same on the Linux test runner as on Windows),
/// so a test holds it. A field with a problem shows a Danger edge and this sentence under the box and
/// is NOT saved; the text stays as typed so the player can fix it.
/// </summary>
public static class FieldRules
{
    public const int MinWidth = 320;
    public const int MinHeight = 240;

    /// <summary>A window size: a whole number of at least <paramref name="minimum"/> pixels.</summary>
    public static string? ResolutionProblem(string? text, int minimum, string what)
    {
        if (!int.TryParse(text?.Trim(), out var value)) return $"{what} must be a whole number.";
        if (value < minimum) return $"{what} must be at least {minimum} pixels.";
        return null;
    }
}
