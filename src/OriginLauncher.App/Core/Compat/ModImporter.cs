using System.IO;

namespace OriginLauncher.App.Core.Compat;

/// <summary>Where an add-a-mod-jar request lands and what it is checked against.</summary>
/// <param name="Folder">The folder the jars are copied into (a version's shared mods folder, or one profile's).</param>
/// <param name="Platform">The Minecraft version and loader the mods must fit.</param>
/// <param name="IsOriginProvisioned">Origin's own jar names (<c>ModManager.IsOriginProvisioned</c>).</param>
/// <param name="IsProfile">The folder is a profile's own: it holds only the player's mods, and Origin's jars are never taken into it.</param>
/// <param name="OriginFolder">For a profile: the shared folder that holds Origin's jars, so the profile's mods are judged beside them. Null = none.</param>
/// <param name="ConfirmedReplace">File names (of the incoming jars) the player has already said "replace" for.</param>
/// <param name="OriginWillInstall">Before a version's first Play (none of Origin's jars in its folder yet): what Origin
/// will install there. A "needs X" note that Origin's X answers is then not said. Null = the folder speaks for itself.</param>
public sealed record ImportContext(
    string Folder, CompatPlatform Platform, Func<string, bool> IsOriginProvisioned, bool IsProfile = false,
    string? OriginFolder = null, IReadOnlySet<string>? ConfirmedReplace = null, BuiltInModSet? OriginWillInstall = null);

public enum ImportAction
{
    /// <summary>Copy it in as a new file.</summary>
    Add,

    /// <summary>Same mod id as one already there and the player confirmed: swap it in, the old one is kept turned off.</summary>
    Replace,

    /// <summary>Same mod id as one already there: ask before changing anything.</summary>
    AskReplace,

    /// <summary>The identical file is already there.</summary>
    AlreadyThere,

    /// <summary>The identical file is there but turned off: dropping it again means "add it back", so it is turned on.</summary>
    TurnOn,

    /// <summary>A profile never takes a second copy of what Origin installs itself.</summary>
    AlreadyOrigin,

    /// <summary>Wrong Minecraft, wrong loader, unreadable, or already part of Origin: not added, with the reason.</summary>
    Refuse,

    /// <summary>Not a .jar (or gone).</summary>
    Skip,
}

public sealed record ImportItem(
    string SourcePath, string FileName, ImportAction Action, string? Reason, ModInfo? Info, ModFile? Existing)
{
    public IReadOnlyList<string> Notes { get; init; } = Array.Empty<string>();
}

/// <summary>A question for the player: this jar is the same mod as one already installed. Everything the prompt needs.</summary>
public sealed record ReplaceOffer(
    string SourcePath, string FileName, string ModName, string? NewVersion, string ExistingFileName, string? ExistingVersion)
{
    /// <summary>The Mods page's Update button made this (a checked Modrinth download in a private folder, not a jar the
    /// player dropped): the page says "Updated" when it lands and deletes the download folder afterwards.</summary>
    public bool IsUpdate { get; init; }

    /// <summary>"Sodium 0.5.0 is already installed (sodium-0.5.0.jar). Replace it with 0.6.13?"</summary>
    public string Question =>
        $"{ModName}{(ExistingVersion == null ? "" : " " + ExistingVersion)} is already installed. "
        + (NewVersion == null ? "Replace it with this file?" : $"Replace it with {NewVersion}?");
}

/// <summary>What an add request did. <see cref="Error"/> null means something was added or is waiting for an answer.</summary>
public sealed record ImportReport(
    int Added, IReadOnlyList<ReplaceOffer> NeedsReplace, IReadOnlyList<string> Refused, IReadOnlyList<string> Notes, string? Error)
{
    public bool Ok => Error == null;

    /// <summary>Jars that were already installed exactly as dropped: nothing was copied, and they are not "added".</summary>
    public int AlreadyInstalled { get; init; }

    /// <summary>Mods (their names) that were there but turned off, dropped again as the identical file: turned back on.</summary>
    public IReadOnlyList<string> TurnedOn { get; init; } = Array.Empty<string>();

    /// <summary>The line the Mods page shows: what was added, then why anything was not.</summary>
    public string Message
    {
        get
        {
            var parts = new List<string>();
            if (Added > 0) parts.Add(Added == 1 ? "Added 1 mod." : $"Added {Added} mods.");
            if (TurnedOn.Count > 0)
                parts.Add(TurnedOn.Count switch
                {
                    1 => $"Turned {TurnedOn[0]} back on.",
                    2 => $"Turned {TurnedOn[0]} and {TurnedOn[1]} back on.",
                    _ => $"Turned {TurnedOn.Count} mods back on.",
                });
            if (AlreadyInstalled > 0) parts.Add(AlreadyInstalled == 1 ? "1 mod was already installed." : $"{AlreadyInstalled} mods were already installed.");
            parts.AddRange(Brief(Refused));
            parts.AddRange(Brief(Notes));
            return string.Join(" ", parts);
        }
    }

    private static IEnumerable<string> Brief(IReadOnlyList<string> lines)
    {
        foreach (var line in lines.Take(2)) yield return line;
        if (lines.Count > 2) yield return $"(and {lines.Count - 2} more)";
    }
}

/// <summary>
/// Adding mod jars to a mods folder, checked by the one compatibility engine first. <see cref="Plan"/> decides (pure
/// apart from reading the jars); <see cref="Apply"/> copies. Rules:
///   * a jar for another Minecraft version, another loader, or one that can't be read is refused with the reason;
///   * a jar that is already part of Origin (same id as a managed mod) is refused;
///   * a jar with the same mod id as one already installed is a QUESTION (<see cref="ImportAction.AskReplace"/>),
///     never a silent overwrite; on yes the old file is kept as foo.jar.disabled, not deleted;
///   * a missing dependency is a note, not a refusal (the player may be adding the library next).
/// Files go in with copy-beside-then-rename, so a game holding the old jar open never has it rewritten under it.
/// </summary>
public static class ModImporter
{
    private const string CloseGameHint = "close Minecraft (on every account playing this version) and try again.";

    // Findings on the jar itself that mean "this can't work here".
    private static readonly HashSet<FindingKind> RefusalKinds = new()
    {
        FindingKind.Unreadable, FindingKind.WrongLoader, FindingKind.WrongMinecraft,
        FindingKind.OriginManagedConflict, FindingKind.ForeignClient,
    };

    // Findings worth telling the player about after the jar went in.
    private static readonly HashSet<FindingKind> NoteKinds = new()
    {
        FindingKind.MissingDependency, FindingKind.WrongVersion, FindingKind.Breaks,
    };

    public static ImportReport Import(IEnumerable<string> sources, ImportContext context)
    {
        var items = Plan(sources, context);
        return Apply(items, context);
    }

    /// <summary>A drop into a profile of the very mod Origin already loads there (same version, or Origin's own mod).</summary>
    public const string AlreadyOriginText = "Origin already includes that mod, so there's no need to add it.";

    public static IReadOnlyList<ImportItem> Plan(IEnumerable<string> sources, ImportContext context)
    {
        var existing = ExistingFor(context);

        var items = new List<ImportItem?>();
        var candidates = new List<(int Index, string Source, string Name, ModFile File)>();
        foreach (var src in sources)
        {
            if (!src.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) || !System.IO.File.Exists(src))
            {
                items.Add(new ImportItem(src, Path.GetFileName(src), ImportAction.Skip, null, null, null));
                continue;
            }
            var name = Path.GetFileName(src);
            var info = ModFolderScanner.ReadInfo(src) ?? new ModInfo { FileName = name, ReadFailed = true };
            candidates.Add((items.Count, src, name, new ModFile(src, name, true, ModRole.ImportCandidate, info)));
            items.Add(null); // decided below, once the whole drop has been looked at together
        }

        if (candidates.Count > 0)
        {
            // A file that is about to take an existing file's name is that file, not a second one.
            var shadowed = candidates.Select(c => c.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
            // (In a profile Origin's jars live in the shared folder, so a file of the same name never takes their place.)
            var entries = existing.Where(e => (context.IsProfile && e.Role == ModRole.Managed) || !shadowed.Contains(JarName(e.FileName)))
                .Concat(candidates.Select(c => c.File)).ToList();
            // The player's own version of a mod Origin ships is theirs to add, in a profile AND in the version's shared
            // folder: it loads instead of Origin's there (Core/Compat/PlayerOverrides; DECISIONS 2026-10-25). The same
            // version, or Origin's own mod, is "already in". Not on 1.8.9 (Forge: no ids to weigh, Origin's stays).
            var report = CompatEngine.Check(entries, PlayerCopiesWin(context) ? context.Platform with { PlayerCopiesWin = true } : context.Platform);

            var firstOfId = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var (index, src, name, file) in candidates)
                items[index] = Decide(src, name, file, report, existing, firstOfId, context);
        }
        return items.Select(i => i!).ToList();
    }

    // A "needs fabric-api" / "needs sodium" before the version's first Play, about a mod Origin installs itself there
    // (in a version that fits the requirement; an unknown version fits any, as in the engine): not a problem, so not
    // said. Players used to add their own copy because of it, which then won over Origin's (bug run 11 PROF-3).
    private static bool OriginWillProvide(CompatFinding finding, ImportContext context)
    {
        if (finding.Kind != FindingKind.MissingDependency || finding.TargetId is not { } id) return false;
        if (context.OriginWillInstall?.Find(id) is not { } mod) return false;
        if (finding.Range == null || mod.Version == null) return true;
        // The finding shows alternatives as "A or B" (VersionPredicate.Display).
        try { return VersionPredicate.Parse(finding.Range.Split(" or ")).IsSatisfiedBy(mod.Version); }
        catch (Exception ex) when (ex is FormatException or ArgumentException) { return false; } // can't tell: keep the note
    }

    // Where the player's own copy of a mod Origin ships may be added (and then loads instead of Origin's).
    private static bool PlayerCopiesWin(ImportContext context) => context.IsProfile || !context.Platform.IsLegacyForge;

    private static ImportItem Decide(
        string src, string name, ModFile file, CompatReport report, List<ModFile> existing,
        Dictionary<string, string> firstOfId, ImportContext context)
    {
        var refusal = report.For(file).FirstOrDefault(f =>
            RefusalKinds.Contains(f.Kind)
            && (f.Severity != Severity.Info || f.Kind == FindingKind.OriginManagedConflict) // for a candidate that one is Info: "would" conflict
            && (f.Kind != FindingKind.WrongMinecraft || f.Certainty == Certainty.Confirmed)); // 1.8.9's mcmod.info is advisory
        if (refusal != null && PlayerCopiesWin(context) && refusal.Kind == FindingKind.OriginManagedConflict
            && (file.Info.MinecraftRange is not { } range || range.IsSatisfiedBy(context.Platform.McVersion)))
            return new ImportItem(src, name, ImportAction.AlreadyOrigin, AlreadyOriginText, file.Info, null);
        if (refusal != null)
            return new ImportItem(src, name, ImportAction.Refuse, $"{name} wasn't added: {refusal.Summary}", file.Info, null);

        var id = file.Info.Id ?? name; // a readable Fabric / Forge jar has one; a jar without is refused above, this is only a guard
        if (firstOfId.TryGetValue(id, out var other))
            return new ImportItem(src, name, ImportAction.Refuse, $"{name} wasn't added: it is the same mod as {other}.", file.Info, null);
        firstOfId[id] = name;

        var twin = existing.FirstOrDefault(e => e.Role != ModRole.Managed && string.Equals(e.Info.Id, id, StringComparison.Ordinal));
        // A profile's folder is its own: Origin's jars (in the shared folder) never share a name with it on disk.
        if (twin == null && existing.Any(e => (!context.IsProfile || e.Role != ModRole.Managed)
                && string.Equals(JarName(e.FileName), name, StringComparison.OrdinalIgnoreCase)))
            return new ImportItem(src, name, ImportAction.Refuse,
                $"{name} wasn't added: a different mod with that file name is already installed. Rename the file and try again.", file.Info, null);
        if (twin != null)
        {
            // The identical file: nothing to copy. Turned off, dropping it again means "add it back" (bug run 11 PROF-9: it
            // used to ask "Replace?" and then keep an identical second copy under an odd -replaced- name). Turning on goes
            // by bytes alone, so a browser's "sodium-0.6.13 (1).jar" copy of the switched-off jar turns that jar on too.
            if (twin.Enabled ? SameFile(src, twin) : SameFile(src, twin, anyName: true))
                return new ImportItem(src, name, twin.Enabled ? ImportAction.AlreadyThere : ImportAction.TurnOn, null, file.Info, twin);
            var action = context.ConfirmedReplace?.Contains(name) == true ? ImportAction.Replace : ImportAction.AskReplace;
            return new ImportItem(src, name, action, null, file.Info, twin);
        }

        var notes = report.For(file).Where(f => NoteKinds.Contains(f.Kind) && !OriginWillProvide(f, context))
            .Select(f => $"{file.DisplayName}: {f.Summary}").Distinct().ToList();
        return new ImportItem(src, name, ImportAction.Add, null, file.Info, null) { Notes = notes };
    }

    /// <summary>
    /// For an update (a newer copy of the installed jar <paramref name="installedFileName"/>): what the new copy needs
    /// that this folder can't give and the installed copy didn't need - a newer Fabric API, Fabric Loader or library
    /// than Origin installs for this version, a missing requirement, a declared conflict - each a finding that would
    /// keep the mod (and so the next Play) from loading. Empty when the update is safe to put in. The same engine and
    /// the same view of the folder as a drop (Plan), with the new copy in the installed one's place. A drop only
    /// NOTES these (the player may add the library next); an update would swap a working jar for a broken one, so the
    /// Mods page keeps the old copy instead (bug run 7, N92-2).
    /// </summary>
    public static IReadOnlyList<CompatFinding> UpdateProblems(string newJar, string installedFileName, ImportContext context)
    {
        var existing = ExistingFor(context);
        var installed = existing.FirstOrDefault(e => e.Role != ModRole.Managed
            && string.Equals(JarName(e.FileName), JarName(installedFileName), StringComparison.OrdinalIgnoreCase));
        var info = ModFolderScanner.ReadInfo(newJar);
        if (installed == null || info == null || info.ReadFailed) return Array.Empty<CompatFinding>();
        var candidate = new ModFile(newJar, Path.GetFileName(newJar), true, ModRole.ImportCandidate, info);
        var platform = PlayerCopiesWin(context) ? context.Platform with { PlayerCopiesWin = true } : context.Platform;

        static bool Breaking(CompatFinding f) =>
            NoteKinds.Contains(f.Kind) && f.Severity == Severity.Blocking && f.Certainty == Certainty.Confirmed;
        static string Key(CompatFinding f) => $"{f.Kind}|{f.TargetId}";
        var before = CompatEngine.Check(existing, platform).For(installed).Where(Breaking).Select(Key).ToHashSet(StringComparer.Ordinal);
        var swapped = existing.Where(e => !ReferenceEquals(e, installed)).Append(candidate).ToList();
        return CompatEngine.Check(swapped, platform).For(candidate)
            .Where(f => Breaking(f) && !before.Contains(Key(f)))
            .GroupBy(Key).Select(g => g.First()).ToList();
    }

    // The folder as Plan sees it: its own jars, plus Origin's (shared folder) jars for a profile.
    private static List<ModFile> ExistingFor(ImportContext context)
    {
        var role = context.IsProfile ? ModRole.Profile : ModRole.SharedLoose;
        var existing = ModFolderScanner.Scan(context.Folder, role, context.IsProfile ? null : context.IsOriginProvisioned);
        if (context.IsProfile && context.OriginFolder != null)
            existing.AddRange(ModFolderScanner.Scan(context.OriginFolder, ModRole.SharedLoose, context.IsOriginProvisioned)
                .Where(m => m.Role == ModRole.Managed));
        return existing;
    }

    public static ImportReport Apply(IReadOnlyList<ImportItem> items, ImportContext context)
    {
        try { Directory.CreateDirectory(context.Folder); }
        catch (Exception ex) { return Failed($"Couldn't open the mods folder: {ex.Message}"); }

        int added = 0, already = 0, skipped = 0;
        var turnedOn = new List<string>();
        var offers = new List<ReplaceOffer>();
        var refused = new List<string>();
        var notes = new List<string>();
        var originInstalled = false;

        foreach (var item in items)
        {
            switch (item.Action)
            {
                case ImportAction.Skip: skipped++; break;
                case ImportAction.AlreadyOrigin: originInstalled = true; break;
                case ImportAction.Refuse: refused.Add(item.Reason!); break;
                case ImportAction.AlreadyThere: already++; break;
                case ImportAction.TurnOn:
                    // A rename (X.jar.disabled -> X.jar), the same as the page's switch, never a rewrite of the jar.
                    // The twin's own name: the dropped copy may be named differently ("sodium-0.6.13 (1).jar").
                    var turned = Mods.ModManager.SetEnabledIn(context.Folder, JarName(item.Existing!.FileName), enabled: true);
                    if (!turned.Ok) refused.Add(turned.Error ?? "Couldn't turn that mod back on - " + CloseGameHint);
                    else turnedOn.Add(item.Info?.Name ?? item.Info?.Id ?? item.FileName);
                    break;
                case ImportAction.AskReplace:
                    offers.Add(new ReplaceOffer(item.SourcePath, item.FileName, item.Info?.Name ?? item.Info?.Id ?? item.FileName,
                        item.Info?.Version, item.Existing!.FileName, item.Existing.Info.Version));
                    break;
                case ImportAction.Add:
                case ImportAction.Replace:
                    var error = CopyIn(item, context);
                    if (error != null) return Failed(error, added, offers, refused, notes);
                    added++;
                    notes.AddRange(item.Notes);
                    break;
            }
        }

        if (added == 0 && already == 0 && turnedOn.Count == 0 && offers.Count == 0)
        {
            var error = originInstalled ? "Origin already includes that mod, so there's no need to add it."
                : refused.Count > 0 ? string.Join(" ", refused.Take(2)) + (refused.Count > 2 ? $" (and {refused.Count - 2} more)" : "")
                : skipped > 0 ? "Only .jar mod files can be added." : "No mods to add.";
            return new ImportReport(0, offers, refused, notes, error);
        }
        return new ImportReport(added, offers, refused, notes, null) { AlreadyInstalled = already, TurnedOn = turnedOn };
    }

    private static ImportReport Failed(
        string error, int added = 0, List<ReplaceOffer>? offers = null, List<string>? refused = null, List<string>? notes = null) =>
        new(added, offers ?? new List<ReplaceOffer>(), refused ?? new List<string>(), notes ?? new List<string>(), error);

    // Add and rename only, in this order, so a failure at any step leaves the player exactly what they had:
    //   1. copy the new jar beside as *.jar.download (a failure here changes nothing);
    //   2. a replace renames the old jar to a free *.disabled name (never overwritten, never deleted);
    //   3. the new jar is moved into place WITHOUT overwrite; if that fails, step 2 is rolled back.
    // A game holding the old jar open (any account's) never has it rewritten under it: it is only renamed.
    private static string? CopyIn(ImportItem item, ImportContext context)
    {
        var dest = Path.Combine(context.Folder, item.FileName);
        var temp = dest + ".download"; // a *.jar.download is never listed or loaded
        string? archived = null;
        string? archivedFrom = null;
        try
        {
            System.IO.File.Copy(item.SourcePath, temp, overwrite: true);

            if (item.Action == ImportAction.Replace && item.Existing is { } old)
            {
                var oldName = Path.GetFileName(old.Path);
                var landsOnTheOldName = string.Equals(oldName, item.FileName, StringComparison.OrdinalIgnoreCase)
                    || string.Equals(oldName, item.FileName + ".disabled", StringComparison.OrdinalIgnoreCase);
                // A turned-off old jar with its own name already is out of the way; every other old one gets renamed.
                if (old.Enabled || landsOnTheOldName)
                {
                    archived = FreeDisabledName(old.Path);
                    archivedFrom = old.Path;
                    System.IO.File.Move(old.Path, archived);
                }
            }

            System.IO.File.Move(temp, dest, overwrite: false);
            return null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            if (archived != null && archivedFrom != null)
            {
                try { System.IO.File.Move(archived, archivedFrom, overwrite: false); } catch (Exception) { /* best effort: the renamed copy is still there under its .disabled name */ }
            }
            try { System.IO.File.Delete(temp); } catch (Exception) { /* our own half-copy; best-effort */ }
            return "Couldn't add that mod - " + CloseGameHint;
        }
    }

    // The old jar's turned-off name: foo.jar -> foo.jar.disabled, else foo-replaced-<ticks>.jar.disabled. Never one that
    // exists (an earlier turned-off copy is somebody's file), and it stays a listed, turn-on-able mod.
    private static string FreeDisabledName(string path)
    {
        var dir = Path.GetDirectoryName(path)!;
        var name = Path.GetFileName(path);
        var jar = name.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? name[..^".disabled".Length] : name;
        var plain = Path.Combine(dir, jar + ".disabled");
        if (!string.Equals(plain, path, StringComparison.OrdinalIgnoreCase) && !System.IO.File.Exists(plain)) return plain;
        var stem = jar.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) ? jar[..^".jar".Length] : jar;
        for (var ticks = DateTime.UtcNow.Ticks; ; ticks++)
        {
            var candidate = Path.Combine(dir, $"{stem}-replaced-{ticks}.jar.disabled");
            if (!System.IO.File.Exists(candidate)) return candidate;
        }
    }

    private static string JarName(string fileName) =>
        fileName.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase) ? fileName[..^".disabled".Length] : fileName;

    // Same name and byte for byte the same: dropping a jar that is already there changes nothing. With anyName, the
    // bytes alone decide (turning a switched-off twin back on).
    private static bool SameFile(string source, ModFile existing, bool anyName = false)
    {
        try
        {
            if (!anyName && !string.Equals(Path.GetFileName(source), JarName(existing.FileName), StringComparison.OrdinalIgnoreCase)) return false;
            var a = new FileInfo(source);
            var b = new FileInfo(existing.Path);
            if (!b.Exists || a.Length != b.Length) return false;
            using var sa = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var sb = new FileStream(existing.Path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var bufA = new byte[8192];
            var bufB = new byte[8192];
            int n;
            while ((n = sa.Read(bufA, 0, bufA.Length)) > 0)
            {
                var read = 0;
                while (read < n)
                {
                    var m = sb.Read(bufB, read, n - read);
                    if (m == 0) return false;
                    read += m;
                }
                if (!bufA.AsSpan(0, n).SequenceEqual(bufB.AsSpan(0, n))) return false;
            }
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
