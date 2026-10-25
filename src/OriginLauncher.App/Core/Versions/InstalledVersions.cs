using System.Collections.Concurrent;
using System.IO;
using OriginLauncher.App.Core.GameFolders;

namespace OriginLauncher.App.Core.Versions;

/// <summary>What the version selector shows for one Minecraft version (Phase 3, U3.2).</summary>
public enum InstallState
{
    /// <summary>Not checked yet, or the folder could not be read. Show no badge; never an error.</summary>
    Unknown,

    /// <summary>Nothing on disk. Play downloads it.</summary>
    NotInstalled,

    /// <summary>Some pieces are on disk and some are missing. Play repairs it.</summary>
    Partial,

    /// <summary>Everything is on disk and the Origin jar is the one this launcher ships.</summary>
    Installed,

    /// <summary>Installed, but the Origin jar on disk differs from the one bundled in this launcher. Play swaps it in.</summary>
    UpdateReady,
}

/// <summary>The pieces of an install, as Play provisions them.</summary>
[Flags]
public enum InstallPieces
{
    None = 0,

    /// <summary>versions/&lt;mc&gt;/&lt;mc&gt;.json and the client jar next to it.</summary>
    Client = 1,

    /// <summary>The loader: a Fabric Loader profile (0.15+), or on 1.8.9 the Forge profile and its universal jar.</summary>
    Loader = 2,

    /// <summary>mods/originclient.jar in the version's shared mods folder.</summary>
    OriginMod = 4,

    /// <summary>
    /// INFORMATIONAL ONLY, set in Present, never in Missing, and never part of the Partial rule: InstallVerification
    /// holds a valid stamp (a full verify passed under this launcher build within the last week). Its absence is
    /// <see cref="InstalledVersionInfo.VerifyDue"/>; Play re-verifies by itself, so nothing is shown for it.
    /// </summary>
    Verified = 8,
}

/// <summary>One version's last-checked install state. Immutable; safe to hand to the UI thread.</summary>
public sealed record InstalledVersionInfo(
    string Version,
    InstallState State,
    InstallPieces Present,
    InstallPieces Missing,
    DateTime CheckedUtc,
    bool VerifyDue = false)
{
    // VerifyDue: every file is there (Installed or UpdateReady) but InstallVerification has no valid stamp (a launcher
    // update or 7 days since the last full verify), so the next Play re-verifies the install first. The state stays
    // driven by real files only; the selector shows nothing for this.
}

/// <summary>Where a legacy (Forge) version keeps its loader: the profile id under versions/ and the universal jar under libraries/.</summary>
public sealed record LegacyLoaderLayout(string VersionId, string LibraryRelativePath);

/// <summary>
/// Which of the launcher's versions are installed, partly installed, missing or due an Origin update,
/// read from disk and cached. The selector asks <see cref="StateOf"/> (cache only, never touches disk) and
/// calls the Refresh methods (disk, on a worker thread) when it opens and after a launch or install.
///
/// What "installed" means is exactly what VersionManager provisions, so this never disagrees with Play:
///   Client    instances/&lt;v&gt;/versions/&lt;v&gt;/&lt;v&gt;.json + &lt;v&gt;.jar
///   Loader    Fabric: instances/&lt;v&gt;/versions/fabric-loader-*-&lt;v&gt;/ with a Loader that reads fabric.modsFolder
///             (SharedMods.NewestInstalledLoader, the same rule the launch uses; an older one is reinstalled).
///             1.8.9: the bundled-JSON Forge profile + its universal jar under libraries/.
///   OriginMod instances/&lt;v&gt;/mods/originclient.jar, only for a version that has an Origin build.
/// Fabric API, the perf stack and OptiFine are not checked: Play adds them quietly and the player never
/// sees them as a separate state.
///
/// READ-ONLY. It never creates, writes or deletes anything and takes no lock the game could be holding.
/// Every failure is an <see cref="InstallState.Unknown"/> result; nothing here throws to the caller.
/// </summary>
public sealed partial class InstalledVersions
{
    /// <summary>The file name Play installs the Origin jar under (VersionManager.InstallOriginJar).</summary>
    public const string OriginJarFileName = "originclient.jar";

    private readonly IReadOnlyList<string> _versions;
    private readonly Func<string, string> _versionRoot;
    private readonly Func<string, string> _modsDir;
    private readonly Func<string, string?> _bundledOriginJar;
    private readonly Func<string, LegacyLoaderLayout?> _legacyLoader;
    private readonly Func<string, string, bool>? _hasVerifiedStamp;
    private readonly Action<string> _ensureListable;

    private readonly ConcurrentDictionary<string, InstalledVersionInfo> _cache = new(StringComparer.Ordinal);
    // Each probe takes a ticket before it reads the disk and only stores its result if no newer probe
    // of the same version already stored one, so a slow refresh can never overwrite a fresher answer.
    private readonly ConcurrentDictionary<string, long> _stored = new(StringComparer.Ordinal);
    private long _ticket;

    /// <param name="versions">Every version the selector lists (VersionCatalog.SupportedVersions).</param>
    /// <param name="versionRoot">A version's shared root (OriginPaths.VersionRoot).</param>
    /// <param name="modsDir">A version's shared mods folder (OriginPaths.ModsDir).</param>
    /// <param name="bundledOriginJar">The Origin jar this launcher ships for a version, or null if the version has no Origin build.
    /// The path need not exist (a dev build without the module output): then the jar is neither required nor compared.</param>
    /// <param name="legacyLoader">The Forge layout for a legacy version (1.8.9), or null for a Fabric version.</param>
    /// <param name="hasVerifiedStamp">(version root, version) -> true when InstallVerification would take the fast path, i.e. Play
    /// has nothing to re-verify. Null = not checked (tests of the other pieces).</param>
    /// <param name="ensureListable">Throws if a folder exists but cannot be listed. Null = list its first entry. A seam so
    /// the unreadable-folder case is testable on any OS.</param>
    public InstalledVersions(
        IReadOnlyList<string> versions,
        Func<string, string> versionRoot,
        Func<string, string> modsDir,
        Func<string, string?> bundledOriginJar,
        Func<string, LegacyLoaderLayout?> legacyLoader,
        Func<string, string, bool>? hasVerifiedStamp = null,
        Action<string>? ensureListable = null)
    {
        _versions = versions;
        _versionRoot = versionRoot;
        _modsDir = modsDir;
        _bundledOriginJar = bundledOriginJar;
        _legacyLoader = legacyLoader;
        _hasVerifiedStamp = hasVerifiedStamp;
        _ensureListable = ensureListable ?? DefaultEnsureListable;
    }

    // File.Exists / Directory.Exists swallow access errors and say "missing"; listing a folder does not.
    private static void DefaultEnsureListable(string dir)
    {
        if (!Directory.Exists(dir)) return;
        using var entries = Directory.EnumerateFileSystemEntries(dir).GetEnumerator();
        entries.MoveNext(); // throws UnauthorizedAccessException / IOException when unreadable
    }

    /// <summary>
    /// Raised (on a worker thread, not the UI thread) when a refresh found a different answer than the cache
    /// held, with the version. Marshal to the dispatcher before touching UI.
    /// </summary>
    public event Action<string>? Changed;

    /// <summary>The cached state. <see cref="InstallState.Unknown"/> until the version was refreshed once. Never touches disk.</summary>
    public InstallState StateOf(string version) =>
        _cache.TryGetValue(version, out var info) ? info.State : InstallState.Unknown;

    /// <summary>The cached result with the missing pieces, or null if the version was never refreshed.</summary>
    public InstalledVersionInfo? Get(string version) => _cache.TryGetValue(version, out var info) ? info : null;

    /// <summary>
    /// Forget one cached answer (it reads Unknown again until the next refresh). A probe already reading the
    /// disk when this runs holds an older ticket than the one recorded here, so its answer is dropped.
    /// </summary>
    public void Invalidate(string version)
    {
        lock (_stored)
        {
            _stored[version] = Interlocked.Increment(ref _ticket);
            _cache.TryRemove(version, out _);
        }
    }

    /// <summary>Re-checks one version on a worker thread and updates the cache. Never throws.</summary>
    public Task RefreshAsync(string version) => Task.Run(() => CheckAndStore(version));

    /// <summary>Re-checks every listed version on a worker thread and updates the cache. Never throws.</summary>
    public Task RefreshAllAsync() => Task.Run(() =>
    {
        foreach (var version in _versions)
            CheckAndStore(version);
    });

    /// <summary>
    /// Reads one version's disk state right now. Synchronous disk work: call it off the UI thread, or use
    /// <see cref="RefreshAsync(string)"/>. Does not touch the cache. Never throws.
    /// </summary>
    public InstalledVersionInfo Check(string version) => Evaluate(version);

    private void CheckAndStore(string version)
    {
        var ticket = Interlocked.Increment(ref _ticket); // before the disk read, see _stored
        Store(Evaluate(version), ticket);
    }

    private void Store(InstalledVersionInfo info, long ticket)
    {
        InstalledVersionInfo? previous = null;
        lock (_stored)
        {
            if (_stored.TryGetValue(info.Version, out var last) && last > ticket) return;
            _stored[info.Version] = ticket;
            _cache.TryGetValue(info.Version, out previous);
            _cache[info.Version] = info;
        }
        if (previous == null || previous.State != info.State || previous.Missing != info.Missing || previous.VerifyDue != info.VerifyDue)
        {
            try { Changed?.Invoke(info.Version); } catch { /* a listener's bug must not break a refresh */ }
        }
    }

    private InstalledVersionInfo Evaluate(string version)
    {
        try
        {
            var root = _versionRoot(version);

            // A file where the version folder should be, or any folder on the way that we may not list, is
            // "cannot tell", not "not installed": File.Exists/Directory.Exists swallow access errors and would
            // say missing, and Play must not be promised a clean download over something we could not read.
            if (File.Exists(root)) return Result(version, InstallState.Unknown, InstallPieces.None, InstallPieces.None);
            var instances = Path.GetDirectoryName(root);
            if (!string.IsNullOrEmpty(instances)) _ensureListable(instances);
            if (!Directory.Exists(root))
                return Result(version, InstallState.NotInstalled, InstallPieces.None, Required(version));
            _ensureListable(root);
            _ensureListable(Path.Combine(root, "versions"));
            if (_legacyLoader(version) != null) _ensureListable(Path.Combine(root, "libraries"));
            _ensureListable(_modsDir(version));

            var present = InstallPieces.None;
            if (ClientPresent(root, version)) present |= InstallPieces.Client;
            if (LoaderPresent(root, version)) present |= InstallPieces.Loader;

            var installedJar = Path.Combine(_modsDir(version), OriginJarFileName);
            var installedOrigin = new FileInfo(installedJar);
            if (installedOrigin.Exists && installedOrigin.Length > 0) present |= InstallPieces.OriginMod;

            var required = Required(version);
            var missing = required & ~present;

            if (present == InstallPieces.None)
                return Result(version, InstallState.NotInstalled, present, required);

            if (missing != InstallPieces.None)
                return Result(version, InstallState.Partial, present, missing);

            // Every file is there. The stamp does not change the state (after each launcher update every stamp is
            // stale, and that must not make every version look broken); it only says Play will verify first.
            var verifyDue = false;
            if (_hasVerifiedStamp != null)
            {
                if (_hasVerifiedStamp(root, version)) present |= InstallPieces.Verified;
                else verifyDue = true;
            }

            return Result(version, OriginJarIsStale(version, installedOrigin) ? InstallState.UpdateReady : InstallState.Installed,
                present, InstallPieces.None, verifyDue);
        }
        catch
        {
            return Result(version, InstallState.Unknown, InstallPieces.None, InstallPieces.None);
        }
    }

    private InstalledVersionInfo Result(string version, InstallState state, InstallPieces present, InstallPieces missing, bool verifyDue = false) =>
        new(version, state, present, missing, DateTime.UtcNow, verifyDue);

    // Client and Loader always; the Origin jar only when this launcher has one for the version on disk
    // (a dev build without the module output could never install it, and must not show Partial forever).
    private InstallPieces Required(string version)
    {
        var required = InstallPieces.Client | InstallPieces.Loader;
        if (_bundledOriginJar(version) is { } bundled && File.Exists(bundled)) required |= InstallPieces.OriginMod;
        return required;
    }

    private static bool ClientPresent(string root, string version)
    {
        var dir = Path.Combine(root, "versions", version);
        return NonEmpty(Path.Combine(dir, version + ".json")) && NonEmpty(Path.Combine(dir, version + ".jar"));
    }

    // A file that exists but is 0 bytes is a write that never finished (or a wiped file): as good as missing.
    private static bool NonEmpty(string path)
    {
        var info = new FileInfo(path);
        return info.Exists && info.Length > 0;
    }

    private bool LoaderPresent(string root, string version)
    {
        var versions = Path.Combine(root, "versions");
        if (_legacyLoader(version) is { } forge)
        {
            return NonEmpty(Path.Combine(versions, forge.VersionId, forge.VersionId + ".json"))
                   && NonEmpty(Path.Combine(root, "libraries", forge.LibraryRelativePath));
        }

        if (!Directory.Exists(versions)) return false;
        var names = Directory.EnumerateDirectories(versions)
            .Select(Path.GetFileName)
            .Where(n => n != null && NonEmpty(Path.Combine(versions, n, n + ".json")))
            .Select(n => n!);
        return SharedMods.NewestInstalledLoader(names, version) != null;
    }

    // Size + last-write time, the same test VersionManager.IsSameFile uses to decide whether Play swaps the
    // jar in (it copies with the bundled jar's timestamp), so "Update ready" is true exactly when Play would
    // replace it. Hashing the jars (several MB each, 30 versions) on every refresh would cost far more than
    // the answer is worth; two different builds with the same size and the same timestamp don't occur.
    private bool OriginJarIsStale(string version, FileInfo installed)
    {
        if (_bundledOriginJar(version) is not { } bundledPath) return false;
        var bundled = new FileInfo(bundledPath);
        if (!bundled.Exists || !installed.Exists) return false;
        return bundled.Length != installed.Length || bundled.LastWriteTimeUtc != installed.LastWriteTimeUtc;
    }
}
