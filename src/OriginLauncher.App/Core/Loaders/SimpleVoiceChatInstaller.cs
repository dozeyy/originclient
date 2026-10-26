using System.IO;
using System.Net.Http;

namespace OriginLauncher.App.Core.Loaders;

// Simple Voice Chat (henkelmax) is auto-added to every Fabric instance the same
// way Fabric API is: a live Modrinth query for the build matching the EXACT MC
// version being installed, downloaded standalone into the mods folder. It is NOT
// a PerformanceModCatalog entry (that list is the hand-pinned perf stack) and it
// is deliberately NOT bundled jar-in-jar on most versions: SVC publishes narrow
// per-version builds (e.g. fabric-1.21.4 declares game_versions ["1.21.4"] only),
// so a single nested jar cannot span an Origin version group (the 1.21.4 build
// also serves 1.21.2/1.21.3) — Fabric would refuse to boot the off-versions.
// Installing the exact-version jar per instance sidesteps that entirely.
//
// The one exception is 1.21.1, whose Origin build bundles SVC jar-in-jar; the
// caller skips this installer for that build (see VersionManager's
// originBundlesPerfStack gate) so Fabric never sees two copies of the mod id.
// Fail-soft: offline, or no SVC build for this MC version → the instance is left
// without voice chat rather than failing the whole install.
public static class SimpleVoiceChatInstaller
{
    private const string ProjectSlug = "simple-voice-chat";
    private const string FilePrefix = "voicechat-"; // SVC jars are named voicechat-fabric-<mc>-<ver>.jar
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var http = new HttpClient();
        // Modrinth asks every client to send a descriptive User-Agent — untagged
        // traffic is more likely to be rate-limited.
        http.DefaultRequestHeaders.UserAgent.ParseAdd("OriginLauncher/1.0 (+will@willhenry.me)");
        return http;
    }

    // 2026-10-25 (Phase 5.2a): the exact pinned file (ManagedPins.Data.cs, Modrinth's SHA-512) first; the live
    // query only for a version with no pin, and it too must match the SHA-512 Modrinth published. Before this, the
    // jar was installed with no hash check, and an unreadable reply or a timeout threw out of the launch.
    public static Task InstallAsync(
        string mcVersion, string modsFolder, IProgress<string>? progress = null, CancellationToken ct = default) =>
        FabricApiInstaller.InstallProjectAsync(Http, ProjectSlug, FilePrefix, "[\"fabric\"]",
            "Installing Simple Voice Chat...", ManagedPins.VoiceChat(mcVersion), mcVersion, modsFolder, progress, ct);
}
