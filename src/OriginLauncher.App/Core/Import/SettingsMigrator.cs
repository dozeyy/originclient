using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using OriginLauncher.App.Core.Import.Bulk;
using OriginLauncher.App.Core.Sync;

namespace OriginLauncher.App.Core.Import;

/// <summary>Everything the settings/pack planner needs: the source, the target, the switches, and the plan it adds to.</summary>
public sealed class MigrationContext
{
    public required SourceFs Fs { get; init; }
    public required DetectedInstance Source { get; init; }
    public required string TargetVersion { get; init; }
    public required string TargetGameDir { get; init; }
    public required ImportOptions Options { get; init; }

    /// <summary>The target has Iris (every Fabric version; not 1.8.9, which has OptiFine).</summary>
    public required bool TargetHasIris { get; init; }
    public required ImportPlan Plan { get; init; }
    public CancellationToken Ct { get; init; }

    /// <summary>What this setup brings (bulk import): Full = everything; ModsAndPacks = no settings, configs only where
    /// nothing else writes them; PacksOnly = resource and shader packs only. A single import is Full.</summary>
    public SetupRole Role { get; init; } = SetupRole.Full;

    /// <summary>What this run already writes into the target folder (shared by every setup of a bulk run's version).</summary>
    public DestinationLedger Ledger { get; init; } = new();

    /// <summary>How this setup is named in "already brought over from ..." lines.</summary>
    public string SetupLabel { get; init; } = "";

    /// <summary>Bulk: the setup whose settings this version gets (a non-winner says so in its summary).</summary>
    public string? SettingsWinnerLabel { get; init; }

    /// <summary>Bulk: this setup's key in the run (a claim's owner); null in a single import, which never defers to anyone.</summary>
    public string? SetupKey { get; init; }
}

// Plans the non-mod part of an import (D15-D19): options.txt, mod configs, the
// resource and shader packs the player actually uses, and (only if asked)
// single-player worlds. Planning only READS the source and the destination; the
// result is a list of PlannedOps the engine executes inside the transaction.
//
// The rules, in short:
//  * options.txt is read with the existing OptionsTxtFormat and written in the
//    TARGET era's format (keybinds to LWJGL codes on 1.8.9, ao/quoting/lang per
//    era); `version` is never copied; resourcePacks is written here, in the
//    target era's form, because the sync format deliberately never carries it.
//  * config/**: everything except Origin's own files, the built-in mods'
//    internal mixin switches and anything credential-looking; sodium-options.json
//    and iris.properties are key-merged, keeping what only the destination has.
//  * a differing destination file is replaced after a backup; "Keep my current
//    Origin settings" keeps it instead.
//  * packs: only the resource packs options.txt selects plus the active shader
//    pack and its settings file (everything with "copy unused packs"). A pack
//    never overwrites another: same bytes -> skipped, different -> "<name>
//    (imported)" and the reference follows.
//  * a bulk import ("import everything") plans several setups into one folder: the winner (Full) as above;
//    the others (ModsAndPacks) bring packs (copied, never selected) and only the config files nothing else
//    writes - never options.txt, servers.dat, iris/sodium or OptiFine settings; a Forge setup on a Fabric
//    version (PacksOnly) brings its packs and nothing else. One DestinationLedger per folder keeps them
//    from landing on each other.
//  * every settings file is stamped "modified now", or Settings Sync's
//    newest-file-wins rule would overwrite the imported keybinds with an older
//    copy from another version on the first Play.
public static class SettingsMigrator
{
    public static void Plan(MigrationContext ctx) => new Planner(ctx).Run();

    // Config files that are Origin's own, or the internal mixin switches of the mods Origin bundles:
    // copying them would fight the bundled versions.
    private static readonly HashSet<string> BuiltInInternalConfigs = new(StringComparer.OrdinalIgnoreCase)
    {
        "sodium-mixins.properties", "lithium.properties", "modernfix-mixins.properties",
        "ferritecore.mixin.properties", "immediatelyfast.json", "krypton.toml",
    };

    private const string IrisFile = "iris.properties";
    private const string SodiumFile = "sodium-options.json";

    private sealed class Planner
    {
        private readonly MigrationContext _c;
        private readonly string _src;
        private readonly string _dst;
        private readonly Dictionary<string, string?> _irisOverrides = new(StringComparer.Ordinal);
        private Dictionary<string, string>? _sourceOptions;
        private string? _resourcePacksValue;
        private string? _irisSourceFile;
        private Dictionary<string, string>? _irisMap;

        public Planner(MigrationContext c)
        {
            _c = c;
            _src = c.Source.GameDir;
            _dst = c.TargetGameDir;
        }

        private ImportPlan Plan => _c.Plan;
        private SourceFs Fs => _c.Fs;
        private bool Target189 => _c.TargetVersion == "1.8.9";
        private bool Winner => _c.Role == SetupRole.Full;

        // Every shader pack, not only the active one: asked for ("copy unused packs"), or a PvP client's setup,
        // whose shader pick lives in the client's own closed settings, so no file here says which pack is in use and
        // "only the active one" would leave every pack behind without a word. Copied, never selected.
        private bool AllShaderPacks => _c.Options.CopyUnusedPacks || Sources.PvpClientSource.BuiltInModsNote(_c.Source.Kind) != null;
        private DestinationLedger Ledger => _c.Ledger;

        public void Run()
        {
            LoadSourceOptions();   // every role: options.txt names the resource packs in use
            PlanResourcePacks();
            PlanShaders();
            if (_c.Options.CopyScreenshots) PlanScreenshots(); // every role: they belong to no version's settings
            if (_c.Role == SetupRole.PacksOnly) { SettingsComeFrom(); return; }
            PlanConfig();
            if (Winner)
            {
                PlanLoneFiles();
                PlanOptions();
            }
            else
            {
                SettingsComeFrom();
            }
            if (_c.Options.CopyWorlds) PlanWorlds();
        }

        // A setup that isn't its version's settings winner says whose settings the version got.
        private void SettingsComeFrom()
        {
            if (_c.SettingsWinnerLabel != null)
                Line(SummarySection.NotImported, PlanItemKind.Setting, $"Settings for Minecraft {_c.TargetVersion} come from {_c.SettingsWinnerLabel}");
        }

        // ---------------------------------------------------------------- options.txt

        private void LoadSourceOptions()
        {
            var path = Path.Combine(_src, "options.txt");
            if (!File.Exists(path) || !Fs.Allow(path)) return;
            _sourceOptions = OptionsTxtFormat.Instance.Read(path);
            if (_sourceOptions == null && Winner)
                Line(SummarySection.NeedsAttention, PlanItemKind.Setting, "options.txt", "Couldn't read the settings file in your old setup.");
        }

        private void PlanOptions()
        {
            if (_sourceOptions == null) return;
            var dest = Path.Combine(_dst, "options.txt");
            var exists = File.Exists(dest);
            if (exists && _c.Options.KeepMyOriginSettings)
            {
                Plan.Kept++;
                Line(SummarySection.NotImported, PlanItemKind.Setting, "options.txt", "You chose to keep your current Origin settings.");
                return;
            }
            Plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.WriteOptions, Item = PlanItemKind.Setting, Label = "options.txt",
                Source = Path.Combine(_src, "options.txt"), Dest = dest, TouchNow = true,
                Desired = _sourceOptions.ToDictionary(kv => kv.Key, kv => (string?)kv.Value, StringComparer.Ordinal),
                TargetVersion = _c.TargetVersion, Extra = _resourcePacksValue,
            });
            if (exists) Plan.Replaced++; else Plan.Created++;
            Line(SummarySection.Imported, PlanItemKind.Setting, "Game settings (options.txt)",
                $"keybinds, video, sound and controls, written for {_c.TargetVersion}");
        }

        // ---------------------------------------------------------------- resource packs

        private void PlanResourcePacks()
        {
            var srcDir = Path.Combine(_src, "resourcepacks");
            var dstDir = Path.Combine(_dst, "resourcepacks");
            var selected = SelectedResourcePacks(srcDir);
            var references = new List<string>();

            foreach (var name in selected)
            {
                _c.Ct.ThrowIfCancellationRequested();
                var path = Path.Combine(srcDir, name);
                if (!File.Exists(path) && !Directory.Exists(path))
                {
                    Line(SummarySection.NeedsAttention, PlanItemKind.ResourcePack, name,
                        "Selected in your old setup, but the pack wasn't found there.");
                    continue;
                }
                if (PlanPack(name, path, dstDir, PlanItemKind.ResourcePack) is { } reference) references.Add(reference);
            }

            if (_c.Options.CopyUnusedPacks)
                foreach (var entry in PackEntries(srcDir))
                    if (!selected.Contains(Path.GetFileName(entry), StringComparer.Ordinal))
                        PlanPack(Path.GetFileName(entry), entry, dstDir, PlanItemKind.ResourcePack);

            if (references.Count == 0) return;
            // Era form: 1.13+ "file/<name>" after "vanilla"; older versions list the bare names.
            var items = McVersion.AtLeast(_c.TargetVersion, "1.13")
                ? new[] { "vanilla" }.Concat(references.Select(r => "file/" + r)).ToArray()
                : references.ToArray();
            _resourcePacksValue = JsonSerializer.Serialize(items);
        }

        // The file packs options.txt selects, in order. Built-in ids ("vanilla", "fabric", ...) are dropped.
        private List<string> SelectedResourcePacks(string srcPacksDir)
        {
            var names = new List<string>();
            if (_sourceOptions == null || !_sourceOptions.TryGetValue("resourcePacks", out var raw)) return names;
            try
            {
                using var doc = JsonDocument.Parse(raw);
                if (doc.RootElement.ValueKind != JsonValueKind.Array) return names;
                foreach (var item in doc.RootElement.EnumerateArray())
                {
                    if (item.ValueKind != JsonValueKind.String) continue;
                    var id = item.GetString()!;
                    string? name = null;
                    if (id.StartsWith("file/", StringComparison.Ordinal)) name = id["file/".Length..];
                    else if (File.Exists(Path.Combine(srcPacksDir, id)) || Directory.Exists(Path.Combine(srcPacksDir, id))) name = id; // 1.8.9 lists bare file names
                    // The list is the source's data, not ours: a name that climbs out of the pack folder is ignored.
                    if (name != null && IsPlainName(name) && !names.Contains(name, StringComparer.Ordinal)) names.Add(name);
                }
            }
            catch (JsonException)
            {
                // an unparseable list: no packs rather than a guess
            }
            return names;
        }

        // ---------------------------------------------------------------- shader packs

        private void PlanShaders()
        {
            var shaderpacks = _c.Source.ShaderPacksFolder;

            // Iris (or Oculus, its Forge twin) keeps the selection in iris.properties; OptiFine in optionsshaders.txt.
            foreach (var candidate in new[] { "iris.properties", "oculus.properties" })
            {
                var file = Path.Combine(_src, "config", candidate);
                if (!File.Exists(file) || !Fs.Allow(file)) continue;
                _irisMap = PropertiesFormat.Iris.Read(file);
                if (_irisMap != null) { _irisSourceFile = file; break; }
            }

            string? active = null;
            bool? enabled = null;
            string? optifineFile = null;
            string? optifineText = null;
            if (_irisMap != null)
            {
                if (_irisMap.TryGetValue("shaderPack", out var raw)) active = ActivePackName(Unescape(raw));
                if (_irisMap.TryGetValue("enableShaders", out var flag)) enabled = !string.Equals(flag.Trim(), "false", StringComparison.Ordinal);
            }
            else
            {
                var of = Path.Combine(_src, "optionsshaders.txt");
                if (File.Exists(of) && Fs.Allow(of))
                {
                    optifineFile = of;
                    optifineText = Fs.ReadText(of);
                    foreach (var line in (optifineText ?? "").Replace("\r\n", "\n").Split('\n'))
                        if (line.StartsWith("shaderPack=", StringComparison.Ordinal))
                            active = ActivePackName(line["shaderPack=".Length..].Trim());
                }
            }

            var hasShaderPacks = Directory.Exists(shaderpacks);
            if (active == null && !AllShaderPacks && optifineFile == null) return;

            if (Target189)
            {
                if (_irisMap != null)
                {
                    Line(SummarySection.NotImported, PlanItemKind.ShaderPack, active ?? "Shader settings",
                        "1.8.9 uses OptiFine shaders, so Iris shader settings don't carry over.");
                    return;
                }
                // OptiFine source onto OptiFine 1.8.9: the settings file as it is, plus the packs.
                string? refName = null;
                if (active != null)
                {
                    if (PackExists(shaderpacks, active)) refName = CopyShaderPack(active, shaderpacks);
                    else Line(SummarySection.NeedsAttention, PlanItemKind.ShaderPack, active,
                        "Your active shader pack wasn't found in your old setup.");
                }
                if (AllShaderPacks) CopyUnusedShaderPacks(shaderpacks, active);
                if (optifineFile != null && Winner) // OptiFine's settings come from the winner only
                {
                    var dest = Path.Combine(_dst, "optionsshaders.txt");
                    if (refName != null && refName != active && optifineText != null)
                    {
                        var rewritten = string.Join("\n", optifineText.Replace("\r\n", "\n").Split('\n')
                            .Select(l => l.StartsWith("shaderPack=", StringComparison.Ordinal) ? "shaderPack=" + refName : l));
                        PlanText(dest, "optionsshaders.txt", PlanItemKind.ShaderPack, rewritten);
                    }
                    else
                    {
                        PlanFile(optifineFile, dest, "optionsshaders.txt", PlanItemKind.ShaderPack);
                    }
                }
                return;
            }

            if (!_c.TargetHasIris)
            {
                if (active != null)
                    Line(SummarySection.NotImported, PlanItemKind.ShaderPack, active, "This version doesn't have shader support.");
                return;
            }

            // Fabric target with Iris: the same mapping whether the source was Iris or OptiFine.
            if (active != null)
            {
                if (!hasShaderPacks || !PackExists(shaderpacks, active))
                {
                    Line(SummarySection.NeedsAttention, PlanItemKind.ShaderPack, active,
                        "Your active shader pack wasn't found in your old setup, so shaders stay as they are.");
                }
                else
                {
                    var refName = CopyShaderPack(active, shaderpacks);
                    if (refName != null)
                    {
                        _irisOverrides["shaderPack"] = refName == active && _irisMap != null && _irisMap.TryGetValue("shaderPack", out var rawName)
                            ? rawName : EscapeValue(refName);
                        _irisOverrides["enableShaders"] = enabled == false ? "false" : "true";
                    }
                }
            }
            if (AllShaderPacks) CopyUnusedShaderPacks(shaderpacks, active);
        }

        // A file name with no folder part: safe to join onto a pack folder.
        private static bool IsPlainName(string name) =>
            name.Length > 0 && name != "." && !name.Contains("..") && name.IndexOfAny(new[] { '/', '\\', '\0', ':' }) < 0
            && name.IndexOfAny(Path.GetInvalidFileNameChars()) < 0;

        private static bool PackExists(string dir, string name) =>
            IsPlainName(name) && (File.Exists(Path.Combine(dir, name)) || Directory.Exists(Path.Combine(dir, name)));

        private static string? ActivePackName(string value) =>
            value.Length == 0 || value is "(internal)" or "OFF" or "off" ? null : value;

        // Copies a shader pack and its <pack>.txt options; returns the name the pack has in the target.
        private string? CopyShaderPack(string name, string shaderpacksDir)
        {
            var dstDir = Path.Combine(_dst, "shaderpacks");
            var path = Path.Combine(shaderpacksDir, name);
            var refName = PlanPack(name, path, dstDir, PlanItemKind.ShaderPack);
            if (refName == null) return null; // refused by the guard: no settings file, no reference either
            // Pack options: shaderpacks/<pack>.txt, where <pack> includes ".zip" for a zipped pack.
            var settings = Path.Combine(shaderpacksDir, name + ".txt");
            if (File.Exists(settings) && Fs.Allow(settings))
                PlanFile(settings, Path.Combine(dstDir, refName + ".txt"), refName + ".txt", PlanItemKind.ShaderPack, touchNow: true);
            return refName;
        }

        private void CopyUnusedShaderPacks(string shaderpacksDir, string? active)
        {
            foreach (var entry in PackEntries(shaderpacksDir))
            {
                var name = Path.GetFileName(entry);
                if (name.EndsWith(".txt", StringComparison.OrdinalIgnoreCase)) continue; // travels with its pack
                if (string.Equals(name, active, StringComparison.Ordinal)) continue;
                CopyShaderPack(name, shaderpacksDir);
            }
        }

        // The packs in a folder: .zip files and folders (a pack's .txt settings travel with the pack).
        private IEnumerable<string> PackEntries(string dir)
        {
            foreach (var file in Fs.EnumerateFiles(dir, recursive: false)
                         .Where(f => !f.EndsWith(".txt", StringComparison.OrdinalIgnoreCase))
                         .OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
                yield return file;
            foreach (var folder in Fs.EnumerateDirectories(dir).OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
                yield return folder;
        }

        // One pack (zip or folder) into <dstDir>: never overwrites. Same bytes -> skip; different -> "<name> (imported)".
        // Returns the name the pack has in the target (what a reference must use).
        private string? PlanPack(string name, string srcPath, string dstDir, PlanItemKind kind)
        {
            // The guard decides before anything reads the pack (even to compare it): a pack whose name looks like
            // a credential file is left out entirely — not copied, not referenced from options.txt / iris.properties —
            // and is named in the summary as skipped for safety (Fs remembers the name; ImportEngine lists it).
            if (!Fs.Allow(srcPath)) return null;
            var isDir = Directory.Exists(srcPath);
            var dest = Path.Combine(dstDir, name);
            var finalName = name;
            string? reason = null;

            // The same pack (same original name, same bytes) from an earlier setup lands once — even when that copy
            // had to be saved as "<name> (imported)" because Origin already had a different pack of the name.
            foreach (var (earlierDest, earlier) in Ledger.PacksNamed(name, isDir))
            {
                var identical = earlier.Source != null && (isDir
                    ? FileCompare.SameTree(srcPath, earlier.Source, Fs)
                    : FileCompare.Same(srcPath, earlier.Source, Fs));
                if (!identical) continue;
                var landedAs = Path.GetFileName(earlierDest);
                var deferred = Line(SummarySection.Imported, kind, landedAs, $"already brought over from {earlier.SetupLabel}");
                if (DefersTo(earlier.Owner))
                    AddFallback(isDir ? OpKind.CopyTree : OpKind.CopyFile, kind, landedAs, srcPath, earlierDest,
                        isDir ? DirectorySize(srcPath) : ImportFiles.SizeOf(srcPath), deferredLine: deferred);
                return landedAs;
            }

            if (Ledger.ClaimOf(dest) is { } claim)
            {
                // Another setup of this run already brings a pack of this name: the same bytes land once.
                var sameAsClaimed = claim.Source != null && claim.IsDirectory == isDir && (isDir
                    ? FileCompare.SameTree(srcPath, claim.Source, Fs)
                    : FileCompare.Same(srcPath, claim.Source, Fs));
                if (sameAsClaimed)
                {
                    var deferred = Line(SummarySection.Imported, kind, name, $"already brought over from {claim.SetupLabel}");
                    // If that setup is skipped mid-run, this one's identical copy lands instead.
                    if (DefersTo(claim.Owner))
                        AddFallback(isDir ? OpKind.CopyTree : OpKind.CopyFile, kind, name, srcPath, dest,
                            isDir ? DirectorySize(srcPath) : ImportFiles.SizeOf(srcPath), deferredLine: deferred);
                    return name;
                }
                finalName = FreeImportedName(dstDir, name, isDir);
                dest = Path.Combine(dstDir, finalName);
                reason = $"a different pack called \"{name}\" is already coming over, so this one is saved as \"{finalName}\"";
            }
            else if (File.Exists(dest) || Directory.Exists(dest))
            {
                var same = isDir
                    ? Directory.Exists(dest) && FileCompare.SameTree(srcPath, dest, Fs)
                    : File.Exists(dest) && FileCompare.Same(srcPath, dest, Fs);
                if (same)
                {
                    Line(SummarySection.Imported, kind, name, "already in Origin (same file), left as it is");
                    return name;
                }
                finalName = FreeImportedName(dstDir, name, isDir);
                dest = Path.Combine(dstDir, finalName);
                reason = $"a different pack called \"{name}\" is already there, so this one is saved as \"{finalName}\"";
            }

            long bytes;
            if (isDir) bytes = DirectorySize(srcPath);
            else if (ImportFiles.SizeOrNull(srcPath) is { } size) bytes = size;
            else
            {
                // It vanished (or became unreadable) since it was listed: left out, never a reason to fail the plan.
                Line(SummarySection.NeedsAttention, kind, name, "Couldn't read this pack in your old setup, so it wasn't imported.");
                return null;
            }
            Ledger.AddPack(name, dest, srcPath, _c.SetupLabel, isDir, _c.SetupKey);
            var packLine = Line(SummarySection.Imported, kind, finalName, reason, bytes);
            Plan.Ops.Add(new PlannedOp
            {
                Kind = isDir ? OpKind.CopyTree : OpKind.CopyFile, Item = kind, Label = finalName,
                Source = srcPath, Dest = dest, Bytes = bytes, ClaimKey = DestinationLedger.PathKey(dest), PlannedLine = packLine,
            });
            return finalName;
        }

        private string FreeImportedName(string dir, string name, bool isDirectory)
        {
            var stem = isDirectory ? name : Path.GetFileNameWithoutExtension(name);
            var ext = isDirectory ? "" : Path.GetExtension(name);
            for (var n = 1; ; n++)
            {
                var candidate = n == 1 ? $"{stem} (imported){ext}" : $"{stem} (imported {n}){ext}";
                var path = Path.Combine(dir, candidate);
                if (!File.Exists(path) && !Directory.Exists(path) && !Ledger.IsClaimed(path)) return candidate;
            }
        }

        // ---------------------------------------------------------------- config/**

        private void PlanConfig()
        {
            var srcConfig = Path.Combine(_src, "config");
            var copied = 0;
            var withheld = 0;
            foreach (var file in Fs.EnumerateFiles(srcConfig, recursive: true).OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
            {
                _c.Ct.ThrowIfCancellationRequested();
                var rel = Path.GetRelativePath(srcConfig, file).Replace('\\', '/');
                var name = Path.GetFileName(file);

                if (rel is IrisFile or "oculus.properties") continue; // key-merged below / Forge-only twin
                if (name.StartsWith("originclient", StringComparison.OrdinalIgnoreCase) && name.EndsWith(".json", StringComparison.OrdinalIgnoreCase)
                    || BuiltInInternalConfigs.Contains(name))
                {
                    withheld++;
                    continue;
                }
                if (rel == SodiumFile)
                {
                    if (Winner) PlanSodium(file); // only the winner's video settings are merged
                    continue;
                }

                var dest = Path.Combine(_dst, "config", rel.Replace('/', Path.DirectorySeparatorChar));
                if (PlanFile(file, dest, "config/" + rel, PlanItemKind.Config, touchNow: true, quiet: true)) copied++;
            }
            if (copied > 0)
                Line(SummarySection.Imported, PlanItemKind.Config, $"Mod settings ({copied} config {(copied == 1 ? "file" : "files")})",
                    Winner ? null : "only the ones Origin didn't have yet");
            if (withheld > 0)
                Line(SummarySection.NotImported, PlanItemKind.Config, $"{withheld} Origin / built-in mod config {(withheld == 1 ? "file" : "files")}",
                    "Origin manages these itself.");

            if (Winner) PlanIris();
        }

        private void PlanSodium(string srcFile)
        {
            var dest = Path.Combine(_dst, "config", SodiumFile);
            if (!File.Exists(dest))
            {
                // Sodium writes its own file on first run and a copied one could carry another version's schema.
                Line(SummarySection.NotImported, PlanItemKind.Config, SodiumFile, "Sodium creates its own settings the first time it runs; set it up once and re-import if you want to carry values over.");
                return;
            }
            if (_c.Options.KeepMyOriginSettings)
            {
                Plan.Kept++;
                Line(SummarySection.NotImported, PlanItemKind.Config, SodiumFile, "You chose to keep your current Origin settings.");
                return;
            }
            var map = Fs.Allow(srcFile) ? JsonConfigFormat.ThirdParty.Read(srcFile) : null;
            if (map == null)
            {
                Line(SummarySection.NeedsAttention, PlanItemKind.Config, SodiumFile, "Couldn't read this settings file in your old setup.");
                return;
            }
            Plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.MergeJson, Item = PlanItemKind.Config, Label = "config/" + SodiumFile,
                Source = srcFile, Dest = dest, TouchNow = true, TargetVersion = _c.TargetVersion,
                Desired = map.ToDictionary(kv => kv.Key, kv => (string?)kv.Value, StringComparer.Ordinal),
            });
            Plan.Replaced++;
            Line(SummarySection.Imported, PlanItemKind.Config, "Sodium video settings", "merged into your current ones");
        }

        private void PlanIris()
        {
            if (_irisMap == null && _irisOverrides.Count == 0) return;
            if (!_c.TargetHasIris) return;
            var dest = Path.Combine(_dst, "config", IrisFile);
            var exists = File.Exists(dest);
            if (exists && _c.Options.KeepMyOriginSettings)
            {
                Plan.Kept++;
                Line(SummarySection.NotImported, PlanItemKind.Config, IrisFile, "You chose to keep your current Origin settings.");
                return;
            }
            var desired = new Dictionary<string, string?>(StringComparer.Ordinal);
            if (_irisMap != null) foreach (var (k, v) in _irisMap) desired[k] = v;
            foreach (var (k, v) in _irisOverrides) desired[k] = v;
            Plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.MergeProperties, Item = PlanItemKind.Config, Label = "config/" + IrisFile,
                Source = _irisSourceFile, Dest = dest, TouchNow = true, TargetVersion = _c.TargetVersion, Desired = desired,
            });
            if (exists) Plan.Replaced++; else Plan.Created++;
            Line(SummarySection.Imported, PlanItemKind.Config, "Shader settings (iris.properties)",
                _irisOverrides.ContainsKey("shaderPack") ? "your shader pack and shader options" : "your shader options");
        }

        // ---------------------------------------------------------------- servers.dat, OptiFine settings

        private void PlanLoneFiles()
        {
            // The server list is the player's own curated data: an existing Origin list is KEPT by default
            // (it is imported only where Origin has none), and "Replace my server list" (Advanced) swaps it
            // after a backup. A byte-identical list is just "already the same".
            var servers = Path.Combine(_src, "servers.dat");
            if (File.Exists(servers) && Fs.Allow(servers))
            {
                var serversDest = Path.Combine(_dst, "servers.dat");
                if (File.Exists(serversDest) && !_c.Options.ReplaceServerList && !FileCompare.Same(servers, serversDest, Fs))
                {
                    Plan.Kept++;
                    Line(SummarySection.NotImported, PlanItemKind.Setting, "servers.dat (server list)",
                        "Your Origin server list was kept; the old list is in Review details.");
                }
                else
                {
                    PlanFile(servers, serversDest, "servers.dat", PlanItemKind.Setting);
                }
            }

            var of = Path.Combine(_src, "optionsof.txt");
            if (!File.Exists(of)) return;
            if (Target189 && Fs.Allow(of))
                PlanFile(of, Path.Combine(_dst, "optionsof.txt"), "optionsof.txt", PlanItemKind.Setting);
            else
                Line(SummarySection.NotImported, PlanItemKind.Setting, "optionsof.txt (OptiFine settings)",
                    "OptiFine settings only apply on 1.8.9. Origin uses Sodium + Iris on this version.");
        }

        // ---------------------------------------------------------------- screenshots (on by default)

        private static readonly HashSet<string> ScreenshotTypes = new(StringComparer.OrdinalIgnoreCase) { ".png", ".jpg", ".jpeg" };

        // Each picture in screenshots/ (the game writes them flat), copied beside Origin's own. Never replaces one:
        // a different picture with the same name lands as "<name> (imported).png". Name + size decide "already here"
        // (a repeat import adds nothing); nothing is hashed, so a folder of thousands plans as fast as listing it.
        // ONE summary line for all of them, not one per picture.
        private void PlanScreenshots()
        {
            var srcDir = _c.Source.ScreenshotsFolder;
            var dstDir = Path.Combine(_dst, "screenshots");
            int already = 0;
            long bytes = 0;
            var copies = new List<(string File, string Dest, long Size)>();
            foreach (var file in Fs.EnumerateFiles(srcDir, recursive: false).OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
            {
                _c.Ct.ThrowIfCancellationRequested();
                var name = Path.GetFileName(file);
                if (!ScreenshotTypes.Contains(Path.GetExtension(name))) continue;
                if (ImportFiles.SizeOrNull(file) is not { } size) continue; // vanished since it was listed
                var dest = ScreenshotDest(file, name, size, dstDir);
                if (dest == null) { already++; continue; }
                Ledger.Add(dest, file, _c.SetupLabel, owner: _c.SetupKey);
                copies.Add((file, dest, size));
                bytes += size;
            }
            if (copies.Count == 0)
            {
                if (already > 0) Line(SummarySection.Imported, PlanItemKind.Screenshot, "Screenshots", "already in Origin, left as they are");
                return;
            }
            // Every copy points at the one line, which apply recounts when it has to leave a picture out.
            var line = Line(SummarySection.Imported, PlanItemKind.Screenshot, ImportSummary.CountName(PlanItemKind.Screenshot, copies.Count),
                already > 0 ? $"{already} already in Origin, left as they are" : null, bytes);
            foreach (var (file, dest, size) in copies)
                Plan.Ops.Add(new PlannedOp
                {
                    Kind = OpKind.CopyFile, Item = PlanItemKind.Screenshot, Label = "screenshots/" + Path.GetFileName(dest),
                    Source = file, Dest = dest, Bytes = size, TouchNow = false, ClaimKey = DestinationLedger.PathKey(dest),
                    PlannedLine = line, CountedLine = true,
                });
        }

        // Where a screenshot lands, or null when it is already there: the same name and size in Origin, under its own
        // name or an earlier "(imported)" one, or already brought by another setup of this run.
        private string? ScreenshotDest(string file, string name, long size, string dstDir)
        {
            var stem = Path.GetFileNameWithoutExtension(name);
            var ext = Path.GetExtension(name);
            for (var n = 0; n < 1000; n++)
            {
                var candidate = Path.Combine(dstDir, n == 0 ? name : n == 1 ? $"{stem} (imported){ext}" : $"{stem} (imported {n}){ext}");
                if (Ledger.ClaimOf(candidate) is { } claim)
                {
                    if (claim.Source != null && Detection.SetupKey.SameFolder(claim.Source, file)) return null; // the same file, already coming
                    if (claim.Source != null && ImportFiles.SizeOrNull(claim.Source) == size
                        && string.Equals(Path.GetFileName(claim.Source), name, StringComparison.OrdinalIgnoreCase)) return null;
                    continue;
                }
                if (Directory.Exists(candidate)) continue;
                if (!File.Exists(candidate)) return candidate;
                if (ImportFiles.SizeOrNull(candidate) == size) return null;
            }
            return null; // a thousand different pictures of one name: leave it out rather than loop on
        }

        // ---------------------------------------------------------------- worlds (opt-in)

        private void PlanWorlds()
        {
            var savesDir = _c.Source.SavesFolder;
            var dstSaves = Path.Combine(_dst, "saves");
            foreach (var world in Fs.EnumerateDirectories(savesDir).OrderBy(w => w, StringComparer.OrdinalIgnoreCase))
            {
                _c.Ct.ThrowIfCancellationRequested();
                if (!File.Exists(Path.Combine(world, "level.dat"))) continue; // not a world
                var name = Path.GetFileName(world);
                // Two setups can share one game folder: a world folder is copied once per run.
                var dest = Path.Combine(dstSaves, name);
                // Imported before and unchanged since (byte for byte): importing again must not add "World1 (imported)".
                if (Directory.Exists(dest) && !Ledger.IsClaimed(dest) && FileCompare.SameTree(world, dest, Fs))
                {
                    Line(SummarySection.Imported, PlanItemKind.World, name, "already in Origin (same world), left as it is");
                    continue;
                }
                if (Directory.Exists(dest) || File.Exists(dest) || Ledger.IsClaimed(dest))
                {
                    // Never merged into another world: a second "New World" is saved beside the first.
                    dest = Path.Combine(dstSaves, FreeImportedName(dstSaves, name, isDirectory: true));
                }
                if (!Ledger.TryClaimWorldSource(world, dest, _c.SetupLabel, _c.SetupKey, out var first))
                {
                    var deferred = Line(SummarySection.Imported, PlanItemKind.World, name, $"already brought over from {first!.SetupLabel}");
                    if (DefersTo(first.Owner))
                        AddFallback(OpKind.CopyTree, PlanItemKind.World, Path.GetFileName(first.Dest), world, first.Dest, DirectorySize(world), deferredLine: deferred);
                    continue;
                }
                var finalName = Path.GetFileName(dest);
                Ledger.Add(dest, world, _c.SetupLabel, isDirectory: true, _c.SetupKey);
                var bytes = DirectorySize(world);
                var worldLine = Line(SummarySection.Imported, PlanItemKind.World, finalName,
                    finalName == name ? null : $"a world called \"{name}\" already exists, so this copy is saved as \"{finalName}\"", bytes);
                Plan.Ops.Add(new PlannedOp
                {
                    Kind = OpKind.CopyTree, Item = PlanItemKind.World, Label = finalName,
                    Source = world, Dest = dest, Bytes = bytes, ClaimKey = DestinationLedger.PathKey(dest), PlannedLine = worldLine,
                });
            }
        }

        // ---------------------------------------------------------------- shared steps

        /// <summary>
        /// One plain file: new -> copy; same bytes -> unchanged; different -> replace after a backup,
        /// or keep the current one with "Keep my current Origin settings". Returns true when it will be copied.
        /// </summary>
        private bool PlanFile(string src, string dest, string label, PlanItemKind kind, bool touchNow = true, bool quiet = false)
        {
            if (ImportFiles.SizeOrNull(src) is not { } length) return false; // vanished since it was listed: nothing to copy
            if (!Winner)
            {
                // Not the settings winner: a file is only CREATED, where neither Origin nor anything earlier in
                // this run writes that path - never replaced, never merged.
                if (File.Exists(dest)) { Plan.Kept++; return false; }
                if (Ledger.ClaimOf(dest) is { } claim)
                {
                    // An earlier setup of this run creates it; if that setup is skipped mid-run, this copy is created instead.
                    Plan.Kept++;
                    if (DefersTo(claim.Owner)) AddFallback(OpKind.CopyFile, kind, label, src, dest, length, touchNow);
                    return false;
                }
                Ledger.Add(dest, src, _c.SetupLabel, owner: _c.SetupKey);
                Plan.Created++;
                var createdLine = quiet ? null : Line(SummarySection.Imported, kind, label);
                Plan.Ops.Add(new PlannedOp
                {
                    Kind = OpKind.CopyFile, Item = kind, Label = label, Source = src, Dest = dest, Bytes = length, TouchNow = touchNow,
                    ClaimKey = DestinationLedger.PathKey(dest), PlannedLine = createdLine,
                });
                return true;
            }
            if (File.Exists(dest))
            {
                if (FileCompare.Same(src, dest, Fs)) { Plan.Unchanged++; return false; }
                if (_c.Options.KeepMyOriginSettings)
                {
                    Plan.Kept++;
                    if (!quiet) Line(SummarySection.NotImported, kind, label, "You chose to keep your current Origin settings.");
                    return false;
                }
                Plan.Replaced++;
            }
            else
            {
                Plan.Created++;
            }
            Ledger.Add(dest, src, _c.SetupLabel, owner: _c.SetupKey);
            var fileLine = quiet ? null : Line(SummarySection.Imported, kind, label);
            Plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.CopyFile, Item = kind, Label = label, Source = src, Dest = dest, Bytes = length, TouchNow = touchNow,
                ClaimKey = DestinationLedger.PathKey(dest), PlannedLine = fileLine,
            });
            return true;
        }

        private void PlanText(string dest, string label, PlanItemKind kind, string text)
        {
            if (File.Exists(dest)) { if (_c.Options.KeepMyOriginSettings) { Plan.Kept++; return; } Plan.Replaced++; } else Plan.Created++;
            Plan.Ops.Add(new PlannedOp
            {
                Kind = OpKind.WriteText, Item = kind, Label = label, Dest = dest, Text = text, TouchNow = true,
                Bytes = Encoding.UTF8.GetByteCount(text),
            });
            Line(SummarySection.Imported, kind, label);
        }

        // A bulk setup defers to another setup's claim (never to its own, never in a single import).
        private bool DefersTo(string? owner) => _c.SetupKey != null && owner != null && owner != _c.SetupKey;

        // The stand-in for a copy another setup claimed: it runs only if that claim is released (ImportEngine.ApplyBulk).
        private void AddFallback(OpKind opKind, PlanItemKind kind, string label, string src, string dest, long bytes, bool touchNow = false,
            SummaryLine? deferredLine = null) =>
            Plan.Ops.Add(new PlannedOp
            {
                Kind = opKind, Item = kind, Label = label, Source = src, Dest = dest, Bytes = bytes, TouchNow = touchNow,
                ClaimKey = DestinationLedger.PathKey(dest), Fallback = true, DeferredLine = deferredLine,
            });

        private SummaryLine Line(SummarySection section, PlanItemKind kind, string name, string? reason = null, long bytes = 0)
        {
            var line = new SummaryLine(section, kind, name, reason, bytes);
            Plan.Lines.Add(line);
            return line;
        }

        private long DirectorySize(string dir)
        {
            long total = 0;
            foreach (var file in Fs.EnumerateFiles(dir, recursive: true))
                total += ImportFiles.SizeOf(file); // a file that vanished mid-scan adds nothing
            return total;
        }
    }

    // ---------------------------------------------------------------- execution helpers (used by ImportEngine)

    /// <summary>Applies the options.txt keys to <paramref name="tempPath"/> (which holds the current file, or does not exist).</summary>
    public static void ApplyOptions(string tempPath, IReadOnlyDictionary<string, string?> desired, string targetVersion, string targetGameDir, string? resourcePacks)
    {
        OptionsTxtFormat.Instance.Apply(tempPath, desired, new SyncInstance(targetVersion, targetGameDir));
        // Null when the target's data version isn't known and the file is new: the engine then says so.
        if (resourcePacks != null && File.Exists(tempPath)) SetOptionsLine(tempPath, "resourcePacks", resourcePacks);
    }

    public static void ApplyJson(string tempPath, IReadOnlyDictionary<string, string?> desired, string targetVersion, string targetGameDir) =>
        JsonConfigFormat.ThirdParty.Apply(tempPath, desired, new SyncInstance(targetVersion, targetGameDir));

    /// <summary>
    /// Key-merges iris.properties: keys the destination has are updated in place (PropertiesFormat.Iris), the
    /// two shader keys are added when the file lacks them, and a missing file is created with the wanted keys.
    /// A shader pack that isn't in the target's shaderpacks/ is never referenced.
    /// </summary>
    public static void ApplyIris(string tempPath, IReadOnlyDictionary<string, string?> desired, string targetVersion, string targetGameDir)
    {
        var instance = new SyncInstance(targetVersion, targetGameDir);
        var existed = File.Exists(tempPath);
        if (existed) PropertiesFormat.Iris.Apply(tempPath, desired, instance);

        var lines = existed ? File.ReadAllText(tempPath, Encoding.Latin1).Replace("\r\n", "\n").Split('\n').ToList() : new List<string>();
        if (lines.Count > 0 && lines[^1].Length == 0) lines.RemoveAt(lines.Count - 1);
        var have = new HashSet<string>(lines.Select(l => l.IndexOf('=') is var i and > 0 ? l[..i].Trim() : ""), StringComparer.Ordinal);
        var changed = false;
        foreach (var (key, value) in desired.OrderBy(kv => kv.Key, StringComparer.Ordinal))
        {
            if (value == null || have.Contains(key)) continue;
            // A new file takes every wanted key; an existing one only gets the two shader keys it lacks.
            if (existed && key is not ("shaderPack" or "enableShaders")) continue;
            if (key == "shaderPack" && !PackPresent(targetGameDir, value)) continue;
            lines.Add(key + "=" + value.Trim());
            changed = true;
        }
        if (changed) AtomicFile.WriteAllBytes(tempPath, Encoding.Latin1.GetBytes(string.Join(Environment.NewLine, lines) + Environment.NewLine));
    }

    private static bool PackPresent(string gameDir, string raw)
    {
        var name = Unescape(raw.Trim());
        if (name.Length == 0) return true;
        if (name.Contains("..") || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return false;
        var path = Path.Combine(gameDir, "shaderpacks", name);
        return File.Exists(path) || Directory.Exists(path);
    }

    // Replaces (or adds) one `key:value` line of an options.txt, keeping the file's line endings.
    private static void SetOptionsLine(string path, string key, string value)
    {
        var text = File.ReadAllText(path, Encoding.UTF8);
        var newline = text.Contains("\r\n") ? "\r\n" : "\n";
        var lines = text.Replace("\r\n", "\n").Split('\n').ToList();
        if (lines.Count > 0 && lines[^1].Length == 0) lines.RemoveAt(lines.Count - 1);
        var index = lines.FindLastIndex(l => l.StartsWith(key + ":", StringComparison.Ordinal));
        if (index >= 0) lines[index] = key + ":" + value; else lines.Add(key + ":" + value);
        AtomicFile.WriteAllText(path, string.Join(newline, lines) + newline);
    }

    // ---- Java .properties text (the Iris shaderPack value) ----

    private static string Unescape(string value)
    {
        var sb = new StringBuilder(value.Length);
        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            if (c != '\\' || i + 1 >= value.Length) { sb.Append(c); continue; }
            var next = value[++i];
            if (next == 'u' && i + 4 < value.Length
                && int.TryParse(value.AsSpan(i + 1, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var code))
            {
                sb.Append((char)code);
                i += 4;
            }
            else
            {
                sb.Append(next switch { 't' => '\t', 'n' => '\n', 'r' => '\r', 'f' => '\f', _ => next });
            }
        }
        return sb.ToString();
    }

    // What Properties.store writes for a value: \ = : # ! and a leading space escaped, non-Latin-1 as \uXXXX.
    private static string EscapeValue(string value)
    {
        var sb = new StringBuilder(value.Length + 4);
        for (var i = 0; i < value.Length; i++)
        {
            var c = value[i];
            switch (c)
            {
                case '\\': sb.Append("\\\\"); break;
                case '=': case ':': case '#': case '!': sb.Append('\\').Append(c); break;
                case ' ' when i == 0: sb.Append("\\ "); break;
                case '\t': sb.Append("\\t"); break;
                case > '~': sb.Append("\\u").Append(((int)c).ToString("X4", CultureInfo.InvariantCulture)); break;
                default: sb.Append(c); break;
            }
        }
        return sb.ToString();
    }
}
