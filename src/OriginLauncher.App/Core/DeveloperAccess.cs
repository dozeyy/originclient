using OriginLauncher.App.Core.Models;

namespace OriginLauncher.App.Core;

/// <summary>
/// The internal developer switch (Settings, Developer) belongs to dev builds only: local builds that CI did not
/// stamp (UpdateService.IsDevBuild). A release build has no way to turn it on, so a value left in
/// settings.json (from a dev build, or from before this rule) is cleared once, at startup.
/// Pure on purpose: the build flag is a parameter so a test can hold both cases.
/// </summary>
public static class DeveloperAccess
{
    /// <summary>True when a release build still holds the offline test switch from somewhere else.</summary>
    public static bool NeedsReset(bool isDevBuild, LauncherSettings settings) =>
        !isDevBuild && settings.OfflineTestMode;

    /// <summary>The change itself; hand it to SettingsStore.Update.</summary>
    public static void ClearOfflineTest(LauncherSettings settings) => settings.OfflineTestMode = false;
}
