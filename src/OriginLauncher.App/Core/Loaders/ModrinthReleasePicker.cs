namespace OriginLauncher.App.Core.Loaders;

// Which of Modrinth's published versions of a project the launcher may install on its own.
// Modrinth lists alphas, betas and releases side by side, and a naive "newest by date" pick
// happily lands on an alpha (Fabric API publishes them for new Minecraft versions). The launcher
// installs RELEASE builds only: an alpha or beta is never picked, even when it is the newest, and
// when a project has no release for the version nothing is installed (fail soft, same as offline).
// Pure on purpose (no network, no WPF) so the rule is unit-tested.
public static class ModrinthReleasePicker
{
    public const string ReleaseType = "release";

    // Newest release among `versions`, or null when there is none. `type` reads Modrinth's
    // "version_type" (release / beta / alpha); a missing type counts as NOT a release.
    public static T? PickNewestRelease<T>(
        IEnumerable<T>? versions, Func<T, string?> type, Func<T, DateTimeOffset> published) where T : class =>
        versions?
            .Where(v => string.Equals(type(v), ReleaseType, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(published)
            .FirstOrDefault();
}
