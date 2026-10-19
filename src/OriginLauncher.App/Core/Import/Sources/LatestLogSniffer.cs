using System.IO;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

/// <summary>A Minecraft version and loader worked out from evidence rather than read from a launcher's own data.</summary>
public sealed record InferredVersion(string? McVersion, LoaderKind Loader, string? LoaderVersion);

// D5: the game's own logs/latest.log as evidence. The parsing lives in
// Detection/LogParser (Fabric, Quilt, Forge, NeoForge, legacy Forge, vanilla
// "Minecraft Version:" lines); every detector now goes through
// Detection/SetupResolver, which weighs the log against the launcher's record and
// the mods (EvidenceJudge) instead of trusting any one of them blindly.
// This class stays as the small convenience API for "what does this log say".
public static class LatestLogSniffer
{
    /// <summary>The version + loader a log's text names, or null when it names no version.</summary>
    public static InferredVersion? Sniff(string? logText) =>
        LogParser.Parse(logText) is { Mc: { } mc } p ? new InferredVersion(mc, p.Loader ?? LoaderKind.None, p.LoaderVersion) : null;

    /// <summary>Reads <paramref name="gameDir"/>/logs/latest.log (shared read: a running game keeps it open).</summary>
    public static InferredVersion? SniffGameDir(string gameDir, SourceFs fs) =>
        Sniff(fs.ReadHeadText(Path.Combine(gameDir, "logs", "latest.log"), LogParser.HeadBytes));
}
