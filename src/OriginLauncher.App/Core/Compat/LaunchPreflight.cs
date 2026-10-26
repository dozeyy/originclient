using System.Threading;
using System.Collections.Concurrent;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace OriginLauncher.App.Core.Compat;

/// <summary>
/// Thrown by <see cref="LaunchPreflight.Run"/> when the mods about to load cannot start the game. The message is the
/// sentence the player reads on the Home page; <see cref="Reasons"/> has one plain line per mod.
/// </summary>
public sealed class IncompatibleModsException : Exception
{
    /// <summary>Longest mod name shown on Home; longer ones are cut with "..." (ASCII only).</summary>
    public const int NameLimit = 20;

    public IncompatibleModsException(string version, IReadOnlyList<string> names, IReadOnlyList<string> reasons, string setKey = "")
        : base(BuildMessage(version, names))
    {
        Version = version;
        Names = names;
        Reasons = reasons;
        SetKey = setKey;
    }

    /// <summary>Identifies the exact mod set that was refused; <see cref="LaunchOverride"/> keeps it for the next Play.</summary>
    public string SetKey { get; }

    public string Version { get; }

    /// <summary>Display name of every mod that would stop the game, in the order found.</summary>
    public IReadOnlyList<string> Names { get; }

    /// <summary>One plain line per mod ("Name: why"), for the log.</summary>
    public IReadOnlyList<string> Reasons { get; }

    /// <summary>
    /// Widest the first line may be on Home, in the estimate of <see cref="EstimateWidth"/> (the status line is 400 px
    /// wide, <c>Size.StatusWidth</c>; the rest is margin for the estimate being a little off).
    /// </summary>
    public const double LineBudget = 392;

    // Home shows this as two rows in a 400 px status line, nothing more:
    //   "<names> (and N more) won't start on <ver>."
    //   "Turn them off on the Mods page, or press Play to start anyway."
    // Row one names two mods when both fit, else one (its name cut to fit), always with the count of the rest. The
    // per-mod reasons stay in Reasons and the preflight log, not on the Home line.
    private static string BuildMessage(string version, IReadOnlyList<string> names)
    {
        var tail = $" won't start on {version}.";
        var line1 = "Some of your mods" + tail;
        for (var take = Math.Min(2, names.Count); take >= 1; take--)
        {
            var shown = names.Take(take).Select(Short).ToList();
            line1 = FirstLine(shown, names.Count - take, tail);
            if (EstimateWidth(line1) <= LineBudget) break;
        }
        // One name still too wide (wide capitals): cut it until it fits.
        if (EstimateWidth(line1) > LineBudget && names.Count > 0)
        {
            var name = Short(names[0]);
            while (name.Length > 4 && EstimateWidth(line1) > LineBudget)
            {
                name = name.Substring(0, name.Length - 4).TrimEnd() + "...";
                line1 = FirstLine(new[] { name }, names.Count - 1, tail);
            }
        }
        // Home also opens the Fix Compatibility prompt over this line (Play/Enter only: UI/Controls/FixCompatibilityOverlay,
        // Core/Components/FixCompatibility); the line stays as the record after the prompt closes.
        return line1 + "\n" + $"Turn {(names.Count > 1 ? "them" : "it")} off on the Mods page, or press Play to start anyway.";
    }

    private static string FirstLine(IReadOnlyList<string> shown, int more, string tail)
    {
        var list = shown.Count == 1 ? shown[0] : $"{shown[0]} and {shown[1]}";
        return list + (more > 0 ? $" (and {more} more)" : "") + tail;
    }

    private static string Short(string name)
    {
        name = name.Trim();
        return name.Length <= NameLimit ? name : name.Substring(0, NameLimit - 3).TrimEnd() + "...";
    }

    /// <summary>
    /// How wide text is on Home (Inter Medium, 12 px), 7 to 10 percent on the high side of the real advance widths:
    /// narrow letters (i, l, j, t, f, r, I, punctuation, space) 4.2 px, wide ones (m, w, M, W, @, %) 11.5, other
    /// capitals 8.6, everything else (lowercase, digits) 7.3. The screen render check (CompatShots) measures the
    /// real line in the real text box.
    /// </summary>
    public static double EstimateWidth(string text)
    {
        var w = 0.0;
        foreach (var c in text)
            w += "iljI.,:;'!|()[] tfr".IndexOf(c) >= 0 ? 4.2
               : "mwMW@%".IndexOf(c) >= 0 ? 11.5
               : c is >= 'A' and <= 'Z' ? 8.6
               : 7.3;
        return w;
    }
}

/// <summary>What the pre-launch check concluded. <see cref="Blocking"/> empty = launch.</summary>
/// <param name="Blocking">One plain line per mod that would stop the game starting (confirmed by its own metadata).</param>
/// <param name="Soft">Everything else worth a log line (never shown to the player).</param>
/// <param name="ModCount">How many jars were in the launch set.</param>
public sealed record LaunchVerdict(IReadOnlyList<string> Blocking, IReadOnlyList<string> Soft, int ModCount)
{
    /// <summary>Display name of each mod in <see cref="Blocking"/>, same order.</summary>
    public IReadOnlyList<string> BlockingNames { get; init; } = Array.Empty<string>();

    /// <summary>Fingerprint of the launch set this verdict is for (names, sizes, times, version).</summary>
    public string SetKey { get; init; } = "";

    public static readonly LaunchVerdict Clear = new(Array.Empty<string>(), Array.Empty<string>(), 0);
}

/// <summary>
/// The compatibility check made right before a launch (Phase 5.1), on the jars the game is about to load. A hard
/// incompatibility (a mod for another Minecraft version, a missing required mod, a declared "breaks", the same mod
/// twice) is turned into an <see cref="IncompatibleModsException"/> so the player gets a sentence instead of a crash
/// on the loader's screen. Rules:
///   * only what the metadata CONFIRMS blocks; a "likely" or advisory finding is a log line, never a stop;
///   * Origin's own jars are never the reason (a bug in the check must not make Origin unbootable);
///   * an Origin-only launch is never checked (the player's mods are not in it);
///   * 1.8.9 (Forge, advisory metadata) is log-only;
///   * if the checker throws, it logs and the launch goes ahead as it did before (fail-soft);
///   * the verdict is remembered by the launch set's fingerprint (names + sizes + times), so a warm launch pays a
///     directory listing and one hash, not a re-check.
/// </summary>
public static class LaunchPreflight
{
    // Findings that mean "the loader will refuse to start". Everything else is advisory here.
    private static readonly HashSet<FindingKind> BlockingKinds = new()
    {
        FindingKind.WrongMinecraft, FindingKind.MissingDependency, FindingKind.WrongVersion,
        FindingKind.Breaks, FindingKind.DuplicateId,
    };

    private const int VerdictCacheLimit = 64;
    private static readonly ConcurrentDictionary<string, LaunchVerdict> Verdicts = new();

    /// <summary>Forgets remembered verdicts (tests that measure a cold check).</summary>
    public static void ClearCache() => Verdicts.Clear();

    /// <summary>1.8.9 runs on Forge in Origin; every other version is Fabric. Java is unknown here (any "java" range is met).</summary>
    public static CompatPlatform PlatformFor(string mcVersion) =>
        new(mcVersion, mcVersion == "1.8.9" ? LoaderFamily.LegacyForge : LoaderFamily.Fabric, null, "");

    /// <summary>
    /// Checks the launch set and throws <see cref="IncompatibleModsException"/> when it cannot start. Anything else
    /// that goes wrong inside the check is logged and ignored.
    /// </summary>
    /// <param name="sharedFolder">The version's shared mods folder (Origin's jars live here).</param>
    /// <param name="profileFolder">The selected mod profile's folder, or null.</param>
    /// <param name="externalMods">False = Origin-only launch: nothing to check.</param>
    /// <param name="isManaged">Origin's own jars (the set a normal launch treats as Origin's).</param>
    /// <param name="isOriginProvisioned">Origin's jars plus voice chat: what a profile launch takes from the shared folder.</param>
    /// <param name="logPath">Where the verdict is written (overwritten each time it is computed), or null.</param>
    /// <param name="ct">Polled between jars; a cancelled launch stops the check (the caller's cancel, not a checker failure).</param>
    /// <param name="overrideSetKey">The set key the player accepted with Play (see <see cref="LaunchOverride"/>), or null.
    /// A blocking verdict for exactly that set is logged and let through; any other set is refused as usual.</param>
    /// <param name="confirmOrigin">Confirms a jar NAMED like Origin's is Origin's copy (full path; ModManager.IsOriginsCopy).</param>
    /// <param name="swap">An alternate component configuration's changes to Origin's side of the set (Core/Components), or null.</param>
    public static void Run(
        string version, string sharedFolder, string? profileFolder, bool externalMods,
        Func<string, bool> isManaged, Func<string, bool> isOriginProvisioned, string? logPath,
        CancellationToken ct = default, string? overrideSetKey = null, Func<string, bool>? confirmOrigin = null,
        LaunchSwap? swap = null)
    {
        try
        {
            var verdict = Evaluate(version, sharedFolder, profileFolder, externalMods, isManaged, isOriginProvisioned, logPath, ct,
                confirmOrigin, swap);
            if (verdict.Blocking.Count > 0)
            {
                // The metadata can be wrong (a false positive must never lock a player out): once the player has seen
                // the sentence and pressed Play on this exact set, the game starts anyway.
                if (!string.IsNullOrEmpty(overrideSetKey) && overrideSetKey == verdict.SetKey)
                {
                    WriteLog(logPath, version, Describe(verdict).Prepend(
                        "THE PLAYER OVERRODE THIS: pressed Play on this exact mod set, so the game was launched anyway."));
                    return;
                }
                throw new IncompatibleModsException(version, verdict.BlockingNames, verdict.Blocking, verdict.SetKey);
            }
        }
        catch (Exception ex) when (ex is IncompatibleModsException || (ex is OperationCanceledException && ct.IsCancellationRequested))
        {
            throw;
        }
        catch (Exception ex)
        {
            WriteLog(logPath, version, new[] { "The compatibility check itself failed; launching as before: " + ex });
        }
    }

    /// <summary>
    /// The check without the throw. A clean verdict is cached by the launch set's fingerprint. Never cached: a
    /// blocking verdict (the player fixes it and presses Play again, so the next check must look afresh), and any
    /// verdict built while a jar could not be read (a half-copied or locked file is a moment, not a fact).
    /// If one of ORIGIN's own jars cannot be read, the check stands down (<see cref="LaunchVerdict.Clear"/>): with it
    /// missing from the picture every mod that needs Sodium or Fabric API would look broken.
    /// With a <paramref name="swap"/>, a swap file that is not fetched yet is judged by what it will say about itself,
    /// and that verdict is not cached (the real file is checked once it is there).
    /// </summary>
    public static LaunchVerdict Evaluate(
        string version, string sharedFolder, string? profileFolder, bool externalMods,
        Func<string, bool> isManaged, Func<string, bool> isOriginProvisioned, string? logPath = null,
        CancellationToken ct = default, Func<string, bool>? confirmOrigin = null, LaunchSwap? swap = null)
    {
        if (!externalMods && profileFolder == null) return LaunchVerdict.Clear;

        var isProfile = profileFolder != null;
        var launchSet = Gather(sharedFolder, profileFolder, isManaged, isOriginProvisioned, confirmOrigin, swap, ct);
        var key = Fingerprint(version, isProfile, launchSet, swap?.Id);
        if (Verdicts.TryGetValue(key, out var cached)) return cached;

        var platform = PlatformFor(version);
        var entries = Read(launchSet, swap, version, logPath, ct, out var anyUnreadable, out var anyExpected);
        if (entries == null) return LaunchVerdict.Clear; // Origin's own jar unreadable: the check stood down (logged)

        var report = CompatEngine.Check(entries, platform);
        var blocking = new List<string>();
        var blockingNames = new List<string>();
        var soft = new List<string>();
        var blockedMods = new HashSet<ModFile>(ReferenceEqualityComparer.Instance);
        foreach (var f in report.Findings)
        {
            if (f.Severity == Severity.Info) continue;
            if (IsHard(f, platform) && blockedMods.Add(f.Subject))
            {
                blocking.Add($"{f.Subject.DisplayName}: {ReasonOf(report, f)}");
                blockingNames.Add(f.Subject.DisplayName);
            }
            else
                soft.Add($"{f.Subject.DisplayName} [{f.Kind}, {f.Severity}, {f.Certainty}]: {f.Summary}");
        }

        var verdict = new LaunchVerdict(blocking, soft, launchSet.Count) { BlockingNames = blockingNames, SetKey = key };
        if (blocking.Count == 0 && !anyUnreadable && !anyExpected)
        {
            if (Verdicts.Count >= VerdictCacheLimit) Verdicts.Clear();
            Verdicts[key] = verdict;
        }
        WriteLog(logPath, version, Describe(verdict));
        return verdict;
    }

    /// <summary>
    /// The full report for the launch set <see cref="Evaluate"/> judges (same jars, roles and metadata), for Fix
    /// Compatibility to plan on. Null when there is nothing to check or Origin's own jar can't be read. Not cached.
    /// </summary>
    public static CompatReport? Inspect(
        string version, string sharedFolder, string? profileFolder, bool externalMods,
        Func<string, bool> isManaged, Func<string, bool> isOriginProvisioned, Func<string, bool>? confirmOrigin = null,
        LaunchSwap? swap = null, CancellationToken ct = default)
    {
        if (!externalMods && profileFolder == null) return null;
        var launchSet = Gather(sharedFolder, profileFolder, isManaged, isOriginProvisioned, confirmOrigin, swap, ct);
        var entries = Read(launchSet, swap, version, null, ct, out _, out _);
        return entries == null ? null : CompatEngine.Check(entries, PlatformFor(version));
    }

    /// <summary>True when <paramref name="f"/> is one the launch check refuses the game for.</summary>
    public static bool Stops(CompatFinding f, CompatPlatform platform) => IsHard(f, platform);

    // The jars a launch loads: Origin's from the shared folder, the player's from the profile (or the shared folder
    // with no profile), with an alternate configuration's swap applied.
    private static List<(string Path, string Name, long Length, long Ticks, ModRole Role)> Gather(
        string sharedFolder, string? profileFolder, Func<string, bool> isManaged, Func<string, bool> isOriginProvisioned,
        Func<string, bool>? confirmOrigin, LaunchSwap? swap, CancellationToken ct)
    {
        var isProfile = profileFolder != null;
        var originTest = isProfile ? isOriginProvisioned : isManaged;
        var launchSet = new List<(string Path, string Name, long Length, long Ticks, ModRole Role)>();
        AddJars(launchSet, sharedFolder, shared: true, isProfile, originTest, confirmOrigin, ct);
        if (isProfile) AddJars(launchSet, profileFolder!, shared: false, isProfile, originTest, confirmOrigin, ct);
        if (swap != null)
        {
            launchSet.RemoveAll(j => j.Role == ModRole.Managed && swap.Replaces(j.Name));
            foreach (var add in swap.Files)
            {
                var file = new FileInfo(add.Path);
                launchSet.Add((add.Path, LaunchSwap.NameOf(add), file.Exists ? file.Length : -1,
                    file.Exists ? file.LastWriteTimeUtc.Ticks : 0, ModRole.Managed));
            }
        }
        return launchSet;
    }

    // Reads each jar's metadata. Null when one of Origin's own jars can't be read (the check stands down, logged).
    private static List<ModFile>? Read(
        List<(string Path, string Name, long Length, long Ticks, ModRole Role)> launchSet, LaunchSwap? swap,
        string version, string? logPath, CancellationToken ct, out bool anyUnreadable, out bool anyExpected)
    {
        anyUnreadable = false;
        anyExpected = false;
        var entries = new List<ModFile>(launchSet.Count);
        foreach (var (path, name, length, _, role) in launchSet)
        {
            ct.ThrowIfCancellationRequested();
            var added = swap?.FileAt(path);
            ModInfo info;
            if (added != null && length < 0)
            {
                info = added.Expected; // not fetched yet
                anyExpected = true;
            }
            else
                info = ModFolderScanner.ReadInfo(path) ?? new ModInfo { FileName = name, ReadFailed = true };
            if (swap != null && role == ModRole.Managed && added == null) info = swap.WithoutReplaced(info);
            if (info.ReadFailed)
            {
                anyUnreadable = true;
                if (role == ModRole.Managed)
                {
                    WriteLog(logPath, version, new[] { $"Origin's own {name} could not be read just now; the compatibility check stood down." });
                    return null;
                }
            }
            entries.Add(new ModFile(path, name, Enabled: true, role, info));
        }
        return entries;
    }

    // A finding stops the game when the metadata says so (Confirmed + Blocking + a kind the loader refuses),
    // it is the mod's own problem rather than a knock-on of another one, and it is not Origin's jar at fault.
    private static bool IsHard(CompatFinding f, CompatPlatform platform)
    {
        if (platform.IsLegacyForge) return false; // mcmod.info is advisory: never a stop
        if (f.Severity != Severity.Blocking || f.Certainty != Certainty.Confirmed) return false;
        if (!BlockingKinds.Contains(f.Kind) || f.IsKnockOn) return false;
        if (f.Subject.Role != ModRole.Managed) return true;
        // Origin's mod declares it breaks a mod of the player's: the player's mod is the one that has to go.
        return f.Kind == FindingKind.Breaks && f.Related is { Role: not ModRole.Managed };
    }

    private static string ReasonOf(CompatReport report, CompatFinding f)
    {
        // A mod short of several things lists them together (the same sentence the Mods page shows).
        if (f.Kind is FindingKind.MissingDependency or FindingKind.WrongVersion && report.ReasonFor(f.Subject) is { } reason)
            return reason;
        return f.Summary;
    }

    private static void AddJars(
        List<(string Path, string Name, long Length, long Ticks, ModRole Role)> into, string folder,
        bool shared, bool isProfile, Func<string, bool> isOriginJar, Func<string, bool>? confirmOrigin, CancellationToken ct)
    {
        DirectoryInfo dir;
        try
        {
            dir = new DirectoryInfo(folder);
            if (!dir.Exists) return;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return;
        }

        IEnumerable<FileInfo> files;
        try { files = dir.EnumerateFiles().ToList(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return; }

        foreach (var file in files.OrderBy(f => f.Name, StringComparer.OrdinalIgnoreCase))
        {
            ct.ThrowIfCancellationRequested();
            if (!file.Name.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) continue; // disabled/partial files never load
            var origin = isOriginJar(file.Name) && (confirmOrigin?.Invoke(file.FullName) ?? true);
            // The same selection LaunchModFolder.LaunchSet makes: a profile launch takes only Origin's jars from the
            // shared folder, and none of Origin's look-alikes from the profile.
            if (isProfile && shared && !origin) continue;
            if (isProfile && !shared && origin) continue;
            var role = origin ? ModRole.Managed : isProfile ? ModRole.Profile : ModRole.SharedLoose;
            into.Add((file.FullName, file.Name, file.Length, file.LastWriteTimeUtc.Ticks, role));
        }
    }

    private static string Fingerprint(
        string version, bool isProfile, List<(string Path, string Name, long Length, long Ticks, ModRole Role)> set,
        string? swapId)
    {
        var sb = new StringBuilder(64 + set.Count * 56);
        sb.Append(version).Append('|').Append(isProfile ? 'p' : 's');
        if (swapId != null) sb.Append('|').Append(swapId); // no swap: the same key as before configurations existed
        sb.Append('\n');
        foreach (var (_, name, length, ticks, role) in set)
            sb.Append(name).Append('|').Append(length).Append('|').Append(ticks).Append('|').Append((int)role).Append('\n');
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString())));
    }

    private static IEnumerable<string> Describe(LaunchVerdict v)
    {
        yield return $"{v.ModCount} mod file(s) checked. {v.Blocking.Count} would stop the game, {v.Soft.Count} other note(s).";
        foreach (var line in v.Blocking) yield return "STOPS THE GAME: " + line;
        foreach (var line in v.Soft) yield return "note: " + line;
    }

    // Best effort: a log that can't be written never matters to the launch.
    private static void WriteLog(string? logPath, string version, IEnumerable<string> lines)
    {
        if (logPath == null) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(logPath)!);
            var text = new StringBuilder();
            text.Append("Origin mod compatibility check, Minecraft ").Append(version).Append(", ")
                .Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")).Append('\n');
            foreach (var line in lines) text.Append(line).Append('\n');
            File.WriteAllText(logPath, text.ToString());
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // nothing to do
        }
    }
}
