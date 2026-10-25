using System.IO;

namespace OriginLauncher.App.Core.Compat;

/// <summary>Where an add-a-mod-jar request lands and what it is checked against.</summary>
/// <param name="Folder">The folder the jars are copied into (a version's shared mods folder, or one profile's).</param>
/// <param name="Platform">The Minecraft version and loader the mods must fit.</param>
/// <param name="IsOriginProvisioned">Origin's own jar names (<c>ModManager.IsOriginProvisioned</c>).</param>
/// <param name="IsProfile">The folder is a profile's own: it holds only the player's mods, and Origin's jars are never taken into it.</param>
/// <param name="OriginFolder">For a profile: the shared folder that holds Origin's jars, so the profile's mods are judged beside them. Null = none.</param>
/// <param name="ConfirmedReplace">File names (of the incoming jars) the player has already said "replace" for.</param>
public sealed record ImportContext(
    string Folder, CompatPlatform Platform, Func<string, bool> IsOriginProvisioned, bool IsProfile = false,
    string? OriginFolder = null, IReadOnlySet<string>? ConfirmedReplace = null);

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

    /// <summary>The line the Mods page shows: what was added, then why anything was not.</summary>
    public string Message
    {
        get
        {
            var parts = new List<string>();
            if (Added > 0) parts.Add(Added == 1 ? "Added 1 mod." : $"Added {Added} mods.");
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

    public static IReadOnlyList<ImportItem> Plan(IEnumerable<string> sources, ImportContext context)
    {
        var role = context.IsProfile ? ModRole.Profile : ModRole.SharedLoose;
        var existing = ModFolderScanner.Scan(context.Folder, role, context.IsProfile ? null : context.IsOriginProvisioned);
        if (context.IsProfile && context.OriginFolder != null)
            existing.AddRange(ModFolderScanner.Scan(context.OriginFolder, ModRole.SharedLoose, context.IsOriginProvisioned)
                .Where(m => m.Role == ModRole.Managed));

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
            if (context.IsProfile && context.IsOriginProvisioned(name))
            {
                items.Add(new ImportItem(src, name, ImportAction.AlreadyOrigin, "Origin already includes that mod, so there's no need to add it.", null, null));
                continue;
            }
            var info = ModFolderScanner.ReadInfo(src) ?? new ModInfo { FileName = name, ReadFailed = true };
            candidates.Add((items.Count, src, name, new ModFile(src, name, true, ModRole.ImportCandidate, info)));
            items.Add(null); // decided below, once the whole drop has been looked at together
        }

        if (candidates.Count > 0)
        {
            // A file that is about to take an existing file's name is that file, not a second one.
            var shadowed = candidates.Select(c => c.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var entries = existing.Where(e => !shadowed.Contains(JarName(e.FileName))).Concat(candidates.Select(c => c.File)).ToList();
            var report = CompatEngine.Check(entries, context.Platform);

            var firstOfId = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var (index, src, name, file) in candidates)
                items[index] = Decide(src, name, file, report, existing, firstOfId, context);
        }
        return items.Select(i => i!).ToList();
    }

    private static ImportItem Decide(
        string src, string name, ModFile file, CompatReport report, List<ModFile> existing,
        Dictionary<string, string> firstOfId, ImportContext context)
    {
        var refusal = report.For(file).FirstOrDefault(f =>
            RefusalKinds.Contains(f.Kind)
            && (f.Severity != Severity.Info || f.Kind == FindingKind.OriginManagedConflict) // for a candidate that one is Info: "would" conflict
            && (f.Kind != FindingKind.WrongMinecraft || f.Certainty == Certainty.Confirmed)); // 1.8.9's mcmod.info is advisory
        if (refusal != null)
            return new ImportItem(src, name, ImportAction.Refuse, $"{name} wasn't added: {refusal.Summary}", file.Info, null);

        var id = file.Info.Id ?? name; // a readable Fabric / Forge jar has one; a jar without is refused above, this is only a guard
        if (firstOfId.TryGetValue(id, out var other))
            return new ImportItem(src, name, ImportAction.Refuse, $"{name} wasn't added: it is the same mod as {other}.", file.Info, null);
        firstOfId[id] = name;

        var twin = existing.FirstOrDefault(e => e.Role != ModRole.Managed && string.Equals(e.Info.Id, id, StringComparison.Ordinal));
        if (twin == null && existing.Any(e => string.Equals(JarName(e.FileName), name, StringComparison.OrdinalIgnoreCase)))
            return new ImportItem(src, name, ImportAction.Refuse,
                $"{name} wasn't added: a different mod with that file name is already installed. Rename the file and try again.", file.Info, null);
        if (twin != null)
        {
            if (SameFile(src, twin) && twin.Enabled)
                return new ImportItem(src, name, ImportAction.AlreadyThere, null, file.Info, twin);
            var action = context.ConfirmedReplace?.Contains(name) == true ? ImportAction.Replace : ImportAction.AskReplace;
            return new ImportItem(src, name, action, null, file.Info, twin);
        }

        var notes = report.For(file).Where(f => NoteKinds.Contains(f.Kind)).Select(f => $"{file.DisplayName}: {f.Summary}").Distinct().ToList();
        return new ImportItem(src, name, ImportAction.Add, null, file.Info, null) { Notes = notes };
    }

    public static ImportReport Apply(IReadOnlyList<ImportItem> items, ImportContext context)
    {
        try { Directory.CreateDirectory(context.Folder); }
        catch (Exception ex) { return Failed($"Couldn't open the mods folder: {ex.Message}"); }

        int added = 0, already = 0, skipped = 0;
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

        if (added == 0 && already == 0 && offers.Count == 0)
        {
            var error = originInstalled ? "Origin already includes that mod, so there's no need to add it."
                : refused.Count > 0 ? string.Join(" ", refused.Take(2)) + (refused.Count > 2 ? $" (and {refused.Count - 2} more)" : "")
                : skipped > 0 ? "Only .jar mod files can be added." : "No mods to add.";
            return new ImportReport(0, offers, refused, notes, error);
        }
        return new ImportReport(added, offers, refused, notes, null) { AlreadyInstalled = already };
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

    // Same name and byte for byte the same: dropping a jar that is already there changes nothing.
    private static bool SameFile(string source, ModFile existing)
    {
        try
        {
            if (!string.Equals(Path.GetFileName(source), JarName(existing.FileName), StringComparison.OrdinalIgnoreCase)) return false;
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
