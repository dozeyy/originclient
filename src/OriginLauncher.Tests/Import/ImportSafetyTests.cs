using System.Text;
using OriginLauncher.App.Core.Import;
using Xunit;

namespace OriginLauncher.Tests.Import;

// What keeps an import safe: it never opens a credential file, never changes the source, can be
// undone exactly (cancel at any point, a failure, a crash), and never reaches outside the folders
// it is given. Every folder is a temp folder; nothing real is touched.
public class ImportSafetyTests : IDisposable
{
    private readonly Rig _rig = new();

    public void Dispose() => _rig.Dispose();

    private string Dst(string relative, string version = "1.21.1") =>
        Path.Combine(_rig.GameDir(version), relative.Replace('/', Path.DirectorySeparatorChar));

    // ------------------------------------------------------------ credentials (criterion 47)

    private static readonly string[] DeniedNames =
    {
        "launcher_accounts.json", "launcher_accounts_microsoft_store.json", "launcher_msa_credentials.bin",
        "launcher_msa_credentials_microsoft_store.bin", "launcher_entitlements.json", "launcher_entitlements_microsoft_store.json",
        "launcher_product_state.json", "launcher_product_state_microsoft_store.json", "launcher_gamer_pics.json",
        "accounts.json", "app.db", "essential-accounts.json", "my-auth-cache.json", "session-data.json",
        "user_token.txt", "client_secret.cfg", "password.txt", "credentials.toml",
    };

    private static readonly string[] Secrets =
    {
        "SECRET-ACCESS-TOKEN-123", "SECRET-CLIENT-TOKEN-XYZ", "TOKEN-IN-ACCOUNTS", "steve@example.com", "CRED-BYTES-777",
    };

    private string BuildOfficialWithEverySecret()
    {
        var mc = Path.Combine(_rig.Root, "dotminecraft");
        Kit.Write(mc, "launcher_profiles.json", """
            {
              "profiles": { "p1": { "name": "Hypixel", "type": "custom", "lastVersionId": "1.21.1", "lastUsed": "2026-10-01T10:00:00.000Z" } },
              "authenticationDatabase": { "abc": { "accessToken": "SECRET-ACCESS-TOKEN-123", "username": "steve@example.com" } },
              "clientToken": "SECRET-CLIENT-TOKEN-XYZ",
              "selectedUser": { "account": "acc1", "profile": "prof1" }
            }
            """);
        Kit.Write(mc, "options.txt", "fov:0.5\n");
        Kit.Write(mc, "config/somemod.json", "{}");
        foreach (var name in DeniedNames)
        {
            // at the game folder's root and inside the folders the import walks
            Kit.Write(mc, name, "TOKEN-IN-ACCOUNTS CRED-BYTES-777");
            Kit.Write(mc, "config/" + name, "TOKEN-IN-ACCOUNTS CRED-BYTES-777");
            Kit.Write(mc, "essential/" + name, "TOKEN-IN-ACCOUNTS");
        }
        Kit.Write(mc, "mods/authlib-extras.jar", "TOKEN-IN-ACCOUNTS");
        return mc;
    }

    [Fact]
    public async Task NoCredentialFileIsEverOpenedOrCopiedAndNoTokenAppearsInAnyOutput()
    {
        var mc = BuildOfficialWithEverySecret();
        var engine = _rig.Engine();

        var scan = await engine.ScanAsync(new SourceRoots { OfficialMinecraftDir = mc }, ImportEngine.DefaultSources(), null, CancellationToken.None);
        var instance = Assert.Single(scan.Instances);
        var request = _rig.Request() with { Source = instance };
        var plan = await engine.PlanAsync(request, null, CancellationToken.None);
        Assert.True(plan.CanApply);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded);

        // 1. never opened: the recording hook saw every source file the engine touched
        Assert.NotEmpty(_rig.Opened);
        Assert.DoesNotContain(_rig.Opened, p => DeniedNames.Contains(Path.GetFileName(p)));
        Assert.Contains(_rig.Opened, p => Path.GetFileName(p) == "launcher_profiles.json"); // the one allowed read, restricted to profile fields

        // 2. never copied
        var copied = Directory.EnumerateFiles(_rig.Instances, "*", SearchOption.AllDirectories).Select(Path.GetFileName).ToList();
        Assert.DoesNotContain(copied, n => DeniedNames.Contains(n!));
        Assert.DoesNotContain(copied, n => n == "launcher_profiles.json");
        Assert.True(File.Exists(Dst("config/somemod.json")));

        // 3. named (names only) as skipped for safety
        Assert.Contains("launcher_accounts.json", plan.SkippedForSafety);
        Assert.Contains("authlib-extras.jar", plan.SkippedForSafety);

        // 4. no secret value in anything the engine produced
        var outputs = new StringBuilder();
        outputs.AppendLine(string.Join("\n", scan.Notes));
        outputs.AppendLine(string.Join("\n", scan.Instances.Select(i => $"{i.Name}|{i.GameDir}|{i.McVersion}")));
        outputs.AppendLine(string.Join("\n", plan.Lines.Select(l => $"{l.Name}|{l.Reason}")));
        outputs.AppendLine(string.Join("\n", plan.Blocked));
        outputs.AppendLine(result.SummaryText);
        outputs.AppendLine(string.Join("\n", _rig.LogLines));
        foreach (var file in Directory.EnumerateFiles(_rig.Imports, "*", SearchOption.AllDirectories))
            outputs.AppendLine(File.ReadAllText(file));
        var all = outputs.ToString();
        foreach (var secret in Secrets) Assert.DoesNotContain(secret, all);
    }

    [Fact]
    public void TheGuardDeniesTheKnownStoresAndAnyCredentialLookingNameAndNothingOrdinary()
    {
        var guard = CredentialGuard.Default;
        foreach (var name in DeniedNames) Assert.True(guard.IsDenied(name), name);
        Assert.True(guard.IsDenied(Path.Combine("x", "y", "Launcher_Accounts.JSON")));
        foreach (var fine in new[] { "options.txt", "servers.dat", "launcher_profiles.json", "sodium-options.json", "minecraftinstance.json", "mymod.jar" })
            Assert.False(guard.IsDenied(fine), fine);
    }

    // ------------------------------------------------------------ the source is never changed (criterion 48)

    [Fact]
    public async Task PlanApplyRollbackPlanApplyNeverChangesTheSourceAndEndsWithTheSameDestination()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.Write(_rig.Source, "logs/latest.log", "log");
        var sourceBefore = Kit.TreeHash(_rig.Source, mtimes: true);
        var request = _rig.Request(options: new ImportOptions { CopyWorlds = true, CopyUnusedPacks = true });
        var engine = _rig.Engine();

        var plan1 = await engine.PlanAsync(request, null, CancellationToken.None);
        Assert.Equal(sourceBefore, Kit.TreeHash(_rig.Source, mtimes: true));
        var applied1 = await engine.ApplyAsync(plan1, _rig.Settings, null, CancellationToken.None);
        Assert.True(applied1.Succeeded);
        Assert.Equal(sourceBefore, Kit.TreeHash(_rig.Source, mtimes: true));
        // Each run's new profile has a fresh id: the listing names it "<profile>".
        string Listing(ImportPlan plan) => string.Join("\n", Directory.EnumerateFiles(_rig.Instances, "*", SearchOption.AllDirectories)
            .Select(f => Path.GetRelativePath(_rig.Instances, f).Replace(plan.ProfileId!, "<profile>")).OrderBy(p => p, StringComparer.Ordinal));
        var listingAfterFirst = Listing(plan1);
        Assert.True(listingAfterFirst.Split('\n').Length > 10);
        Assert.Contains(Path.Combine("1.21.1", "profiles", "<profile>", "mods", "mymod.jar"), listingAfterFirst); // its own profile
        Assert.Single(_rig.Settings.Load().Profiles);

        var undone = await engine.RollbackAsync(plan1.ImportId, _rig.Settings);
        Assert.Equal(ImportState.RolledBack, undone.State);
        Assert.Null(undone.Error);
        Assert.Equal(sourceBefore, Kit.TreeHash(_rig.Source, mtimes: true));
        Assert.False(Directory.Exists(_rig.Instances));                              // everything the import made is gone
        Assert.Empty(_rig.Settings.Load().Profiles);
        Assert.Equal("1.21.11", _rig.Settings.Load().SelectedVersion);               // the selection is put back
        Assert.Null(_rig.Settings.Load().SelectedProfileId);

        var plan2 = await engine.PlanAsync(request, null, CancellationToken.None);
        var applied2 = await engine.ApplyAsync(plan2, _rig.Settings, null, CancellationToken.None);
        Assert.True(applied2.Succeeded);
        Assert.Equal(sourceBefore, Kit.TreeHash(_rig.Source, mtimes: true));

        // The second run lands exactly the same files as the first.
        Assert.Equal(listingAfterFirst, Listing(plan2));
    }

    // ------------------------------------------------------------ every replaced file is backed up (criterion 49)

    [Fact]
    public async Task EveryReplacedDestinationPathHasABackupAndAJournalEntry()
    {
        TypicalSource.Populate(_rig.Source);
        var originals = new Dictionary<string, string>
        {
            ["options.txt"] = "fov:1.0\n",
            ["config/somemod.json"] = """{"a":999}""",
            ["config/nested/x.toml"] = "x=2",
            ["config/iris.properties"] = "shaderPack=\nenableShaders=false\nmaxShadowRenderDistance=8\n",
            ["config/sodium-options.json"] = """{"quality":{"weather_quality":"DEFAULT"},"advanced":{"cpu_render_ahead_limit":1}}""",
        };
        foreach (var (rel, text) in originals) Kit.Write(_rig.GameDir(), rel, text);

        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        var result = await engine.ApplyAsync(plan, _rig.Settings, null, CancellationToken.None);
        Assert.True(result.Succeeded);

        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!;
        var replaced = journal.Entries.Where(e => e.Kind == JournalEntryKinds.Replaced).ToList();
        Assert.Equal(originals.Count, replaced.Count);
        foreach (var (rel, text) in originals)
        {
            var path = Dst(rel);
            Assert.NotEqual(text, File.ReadAllText(path));                           // it really changed
            var entry = Assert.Single(replaced, e => e.Path == path);
            Assert.StartsWith(ImportLayout.Backup(_rig.Imports, plan.ImportId), entry.Backup);
            Assert.Equal(text, File.ReadAllText(entry.Backup!));                     // the old copy, byte for byte
        }
        Assert.Equal(ImportState.Committed, journal.State);
    }

    // ------------------------------------------------------------ cancel anywhere = exactly as before (criterion 50)

    public enum CancelPoint { AfterFirstFile, MidMods, MidSettings, BeforeSettingsDelta }

    [Theory]
    [InlineData(CancelPoint.AfterFirstFile)]
    [InlineData(CancelPoint.MidMods)]
    [InlineData(CancelPoint.MidSettings)]
    [InlineData(CancelPoint.BeforeSettingsDelta)]
    public async Task CancellingAtAnyPointLeavesTheDestinationAndSettingsExactlyAsTheyWere(CancelPoint point)
    {
        TypicalSource.Populate(_rig.Source);
        // Existing destination state the import would replace, plus a shared mods folder.
        Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        Kit.Write(_rig.GameDir(), "config/somemod.json", """{"a":999}""");
        Kit.WriteBytes(_rig.GameDir(), "resourcepacks/Faithful.zip", Kit.Zip(Kit.Text("pack.mcmeta", "MINE")));
        Kit.WriteBytes(Path.Combine(_rig.Instances, "1.21.1", "mods"), "existing.jar", Kit.FabricJar("existing", "1.0"));
        var treeBefore = Kit.TreeHash(_rig.Instances, mtimes: true);
        var settingsBefore = _rig.Settings.FileHash();

        using var cts = new CancellationTokenSource();
        var progress = new SyncProgress(p =>
        {
            var cancel = point switch
            {
                CancelPoint.AfterFirstFile => p.Done == 1,
                CancelPoint.MidMods => p.Stage == ImportStage.Mods && p.Done == 2,
                CancelPoint.MidSettings => p.Stage == ImportStage.Settings && p.Item == "options.txt",
                _ => p.Stage == ImportStage.Finishing,
            };
            if (cancel) cts.Cancel();
        });
        var engine = _rig.Engine();
        var plan = await engine.PlanAsync(_rig.Request(), null, CancellationToken.None);
        Assert.True(plan.Ops.Count(o => o.Item == PlanItemKind.Mod) >= 3);

        var result = await engine.ApplyAsync(plan, _rig.Settings, progress, cts.Token);

        Assert.True(result.Cancelled);
        Assert.Equal(ImportState.RolledBack, result.State);
        Assert.Null(result.Error);
        Assert.Equal(treeBefore, Kit.TreeHash(_rig.Instances, mtimes: true));        // the destination tree, byte for byte, mtimes too
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());                       // the launcher settings file
        Assert.Equal(0, _rig.Settings.ApplyCalls);                                    // the settings change was never made
        var journal = ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!;
        Assert.Equal(ImportState.RolledBack, journal.State);
        Assert.Empty(journal.RollbackProblems);
        Assert.False(Directory.Exists(ImportLayout.Staging(_rig.Imports, plan.ImportId)));
        if (point != CancelPoint.AfterFirstFile) Assert.NotEmpty(journal.Entries);   // something really had been done and was undone
    }

    // ------------------------------------------------------------ crash recovery (criterion 51)

    [Fact]
    public void AnInterruptedImportsJournalIsRolledBackNotResumed()
    {
        var importId = ProfileId();
        var run = ImportLayout.RunDir(_rig.Imports, importId);
        Directory.CreateDirectory(Path.Combine(run, "backup"));

        // The half-finished state a crash would leave: a created file, a filled created folder, a replaced file, applied settings.
        var created = Kit.Write(_rig.Root, "OriginLauncher/instances/1.21.1/accounts/x/new.txt", "NEW");
        var createdDir = Path.Combine(_rig.Root, "OriginLauncher/instances/1.21.1/accounts/x/saves/World");
        Kit.Write(createdDir, "level.dat", "WORLD");
        var replaced = Kit.Write(_rig.Root, "OriginLauncher/instances/1.21.1/accounts/x/options.txt", "IMPORTED");
        var backup = Kit.Write(run, "backup/0001-options.txt", "ORIGINAL");
        var delta = new SettingsDelta
        {
            AddProfile = new App.Core.Profiles.ModProfile { Id = importId, Name = "Folder: X", Version = "1.21.1" },
            SetSelectedVersion = "1.21.1", SetSelectedProfileId = importId,
            PreviousSelectedVersion = "1.21.11", PreviousSelectedProfileId = null,
        };
        var settingsBefore = _rig.Settings.FileHash();
        _rig.Settings.Apply(delta);
        Assert.NotEqual(settingsBefore, _rig.Settings.FileHash());

        var journal = new ImportJournal { ImportId = importId, State = ImportState.Applying, StartedUtc = DateTime.UtcNow };
        journal.Entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = created });
        journal.Entries.Add(new JournalEntry { Kind = JournalEntryKinds.Created, Path = createdDir, IsDirectory = true, Recursive = true });
        journal.Entries.Add(new JournalEntry { Kind = JournalEntryKinds.Replaced, Path = replaced, Backup = backup });
        journal.Entries.Add(new JournalEntry { Kind = JournalEntryKinds.SettingsDelta, Delta = delta });
        ImportJournalStore.Save(journal, ImportLayout.Journal(_rig.Imports, importId));

        // A finished import and an unreadable journal beside it must be left alone.
        var doneId = ProfileId();
        ImportJournalStore.Save(new ImportJournal { ImportId = doneId, State = ImportState.Committed, StartedUtc = DateTime.UtcNow },
            ImportLayout.Journal(_rig.Imports, doneId));
        var brokenRun = ImportLayout.RunDir(_rig.Imports, ProfileId());
        Kit.Write(brokenRun, "journal.json", "{ not json");

        var log = new List<string>();
        var recovered = ImportRecovery.Recover(_rig.Imports, Path.Combine(_rig.Root, "OriginLauncher"), _rig.Settings, log.Add, CancellationToken.None);

        Assert.Equal(1, recovered);
        Assert.False(File.Exists(created));
        Assert.False(Directory.Exists(createdDir));
        Assert.Equal("ORIGINAL", File.ReadAllText(replaced));
        Assert.False(File.Exists(backup));
        Assert.Equal(settingsBefore, _rig.Settings.FileHash());
        Assert.Equal(ImportState.RolledBack, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, importId))!.State);
        Assert.Equal(ImportState.Committed, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, doneId))!.State);
        Assert.Contains($"import {importId}: rolled back interrupted import", log);
        Assert.Contains($"import {importId}: rolled back interrupted import", File.ReadAllText(ImportLayout.Log(_rig.Imports, importId)));

        // Running it again finds nothing more to do.
        Assert.Equal(0, ImportRecovery.Recover(_rig.Imports, Path.Combine(_rig.Root, "OriginLauncher"), _rig.Settings, log.Add, CancellationToken.None));
    }

    [Fact]
    public async Task ARealApplyThatCrashesMidwayIsRecoveredToTheStateBeforeTheImport()
    {
        TypicalSource.Populate(_rig.Source);
        Kit.Write(_rig.GameDir(), "options.txt", "fov:1.0\n");
        var treeBefore = Kit.TreeHash(_rig.Instances, mtimes: true);

        // "Crash": the process dies after the ops, before the commit. The progress callback throws a non-cancel
        // exception at the Finishing step, which the engine would roll back — so instead kill the rollback by making
        // the apply stop WITHOUT cleanup: run the transaction by hand and abandon it.
        var plan = await _rig.Engine().PlanAsync(_rig.Request(), null, CancellationToken.None);
        var logRoot = Path.Combine(_rig.Root, "OriginLauncher");
        var tx = new ImportTransaction(_rig.Imports, logRoot, plan.ImportId, () => DateTime.UtcNow, "x", "1.21.1");
        tx.Begin();
        var fs = new SourceFs();
        foreach (var op in plan.Ops.Take(plan.Ops.Count - 1))
        {
            if (op.Kind == OpKind.CopyFile) tx.CopyFile(op.Source!, op.Dest, op.TouchNow);
            else if (op.Kind == OpKind.CopyTree) tx.CopyTree(fs, op.Source!, op.Dest, CancellationToken.None);
            else if (op.Kind == OpKind.WriteOptions)
                tx.Rewrite(op.Dest, t => App.Core.Import.SettingsMigrator.ApplyOptions(t, op.Desired!, op.TargetVersion!, plan.TargetGameDir, op.Extra), true);
        }
        tx.Dispose();                                                              // abandoned: the journal says Applying
        Assert.NotEqual(treeBefore, Kit.TreeHash(_rig.Instances, mtimes: true));
        Assert.Equal(ImportState.Applying, ImportJournalStore.Load(ImportLayout.Journal(_rig.Imports, plan.ImportId))!.State);

        var recovered = await ImportRecovery.RecoverAsync(_rig.Imports, logRoot, _rig.Settings, null, CancellationToken.None);

        Assert.Equal(1, recovered);
        Assert.Equal(treeBefore, Kit.TreeHash(_rig.Instances, mtimes: true));
    }

    // ------------------------------------------------------------ no reaching outside what it is given (criterion 52)

    [Fact]
    public void NothingInCoreImportOutsideLauncherReadsOriginPathsSettingsOrKnownFolders()
    {
        var dir = AppContext.BaseDirectory;
        string? import = null;
        while (dir != null && import == null)
        {
            var candidate = Path.Combine(dir, "src", "OriginLauncher.App", "Core", "Import");
            if (Directory.Exists(candidate)) import = candidate;
            dir = Path.GetDirectoryName(dir);
        }
        Assert.NotNull(import);

        var forbidden = new[] { "OriginPaths", "GetFolderPath", "LocalApplicationData", "SettingsStore" };
        var files = Directory.EnumerateFiles(import!, "*.cs", SearchOption.AllDirectories)
            .Where(f => !Path.GetRelativePath(import!, f).Replace('\\', '/').StartsWith("Launcher/", StringComparison.Ordinal))
            .ToList();
        Assert.True(files.Count > 10);
        foreach (var file in files)
        {
            var text = File.ReadAllText(file);
            foreach (var word in forbidden)
                Assert.False(text.Contains(word, StringComparison.Ordinal), $"{Path.GetFileName(file)} mentions {word}");
        }
    }

    private static string ProfileId() => App.Core.Profiles.ProfileRules.NewId();
}
