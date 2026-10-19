using System.IO;
using OriginLauncher.App.Core.Import.Detection;

namespace OriginLauncher.App.Core.Import.Sources;

// The Modrinth app: each profile is a folder <root>/profiles/<name>/ and that folder IS the
// game dir. The app keeps everything else (playtime, loader, last played, a relocated data
// folder) in a SQLite database (app.db) which this engine never opens — it is on the
// CredentialGuard denylist and there is no SQLite dependency. So a Modrinth profile's
// version and loader come from its own latest.log, then its mods (D5): always Inferred, and
// with no playtime or last-played, the version picker simply asks.
// Limitation: if the player moved the app's data folder, the root isn't here; Browse finds it.
public sealed class ModrinthSource : IInstanceSource
{
    public SourceKind Kind => SourceKind.Modrinth;

    public Task DetectAsync(SourceRoots roots, SourceFs fs, ScanResult into, CancellationToken ct) =>
        Task.Run(() =>
        {
            if (string.IsNullOrWhiteSpace(roots.ModrinthDir)) return;
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var members = new List<SetupMember>();
            foreach (var root in Roots(roots.ModrinthDir))
            {
                foreach (var profile in fs.EnumerateDirectories(Path.Combine(root, "profiles")).OrderBy(p => p, StringComparer.OrdinalIgnoreCase))
                {
                    ct.ThrowIfCancellationRequested();
                    if (!seen.Add(profile) || !FolderSource.LooksLikeGameDir(profile)) continue;
                    members.Add(new SetupMember(new DetectedInstance
                    {
                        Kind = SourceKind.Modrinth, Name = Path.GetFileName(profile), GameDir = profile,
                        Confidence = Confidence.Inferred,
                    }));
                }
            }
            // No launcher record: the log and the mods decide, and only both agreeing makes it certain (EvidenceJudge V5).
            SetupResolver.AddSetups(into, members, fs, ct);
        }, ct);

    // The app's folder is %APPDATA%\ModrinthApp, or the older com.modrinth.theseus beside it (migrated automatically): look at both.
    private static IEnumerable<string> Roots(string given)
    {
        yield return given;
        var parent = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(given));
        if (!string.IsNullOrEmpty(parent)
            && string.Equals(Path.GetFileName(Path.TrimEndingDirectorySeparator(given)), "ModrinthApp", StringComparison.OrdinalIgnoreCase))
            yield return Path.Combine(parent, "com.modrinth.theseus");
    }
}
