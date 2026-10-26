using OriginLauncher.App.Core.Compat;
using Xunit;
using static OriginLauncher.Tests.Compat.CompatKit;

namespace OriginLauncher.Tests.Compat;

// CompatEngine.Check: one test group per finding kind (a case that fires and a case that must not), then the graph
// behaviours (knock-ons, cycles, jar-in-jar provides) and the 1.8.9 Forge path.
public class CompatEngineTests
{
    // ---- a clean set ----

    [Fact]
    public void AModWhoseRequirementsAreMetHasNoFindings()
    {
        var report = Check(
            Mod("sodium", "0.6.13", ModRole.Managed),
            Mod("sodium-extra", "0.6.0", needs: new[] { ("sodium", ">=0.6"), ("minecraft", "1.21.x") }));
        Assert.True(report.IsClean);
        Assert.False(report.HasBlocking);
        Assert.Empty(report.Findings);
    }

    [Fact]
    public void AnEmptySetIsClean() => Assert.True(Check().IsClean);

    // ---- MissingDependency / WrongVersion ----

    [Fact]
    public void AMissingDependencyIsBlockingAndConfirmed()
    {
        var mod = Mod("addon", needs: new[] { ("lib", "*") });
        var report = Check(mod);
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.MissingDependency, f.Kind);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Equal(Certainty.Confirmed, f.Certainty);
        Assert.True(f.Excludes);
        Assert.Equal("lib", f.TargetId);
        Assert.Equal("Needs lib.", f.Summary);
        Assert.Equal("Needs lib.", report.ReasonFor(mod));
        Assert.Same(mod, f.Subject);
    }

    [Fact]
    public void AWrongVersionNamesTheVersionThatIsThere()
    {
        var mod = Mod("addon", needs: new[] { ("lib", ">=2.0") });
        var report = Check(mod, Mod("lib", "1.4.0"));
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.WrongVersion, f.Kind);
        Assert.Equal("1.4.0", f.Found);
        Assert.Equal(">=2.0", f.Range);
        Assert.Equal("Needs lib >=2.0.", f.Summary);
        Assert.False(f.TargetIsOrigin);
    }

    [Fact]
    public void AWrongVersionOfAnOriginModSaysSo()
    {
        var report = Check(Mod("sodium", "0.6.13", ModRole.Managed), Mod("addon", needs: new[] { ("sodium", "0.8.x") }));
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.WrongVersion, f.Kind);
        Assert.True(f.TargetIsOrigin);
    }

    [Fact]
    public void SeveralUnmetRequirementsAreOneSentence()
    {
        var mod = Mod("addon", needs: new[] { ("zeta", "*"), ("alpha", ">=1") });
        var report = Check(mod);
        Assert.Equal(2, report.Findings.Count);
        Assert.Equal("Needs alpha >=1, Needs zeta.", report.ReasonFor(mod)); // by id, so the order is stable
    }

    [Fact]
    public void AnyProviderOfSeveralVersionsMayMeetTheRequirement()
    {
        // Two mods answer to "lib" (one provides it): the requirement holds if either version fits.
        var report = Check(
            Mod("lib", "1.0.0"), Mod("lib-fork", "3.0.0", provides: new[] { "lib" }),
            Mod("addon", needs: new[] { ("lib", ">=2") }));
        Assert.True(report.IsClean);
    }

    [Fact]
    public void ADisabledProviderDoesNotMeetARequirement()
    {
        var report = Check(Mod("lib", enabled: false), Mod("addon", needs: new[] { ("lib", "*") }));
        Assert.Equal(FindingKind.MissingDependency, Assert.Single(report.Findings).Kind);
    }

    [Fact]
    public void AModThatIsTurnedOffHasNoDependencyFindings()
    {
        var report = Check(Mod("addon", enabled: false, needs: new[] { ("lib", "*") }));
        Assert.Empty(report.Findings);
        Assert.Null(report.Terminal(report.Entries[0]));
    }

    [Theory]
    [InlineData("21", ">=21", true)]
    [InlineData("17", ">=21", false)]
    public void JavaIsPartOfThePlatform(string java, string range, bool ok)
    {
        var platform = Fabric121 with { JavaVersion = java };
        var report = Check(platform, Mod("addon", needs: new[] { ("java", range) }));
        Assert.Equal(ok, report.IsClean);
        if (!ok)
        {
            var f = Assert.Single(report.Findings);
            Assert.Equal(FindingKind.WrongVersion, f.Kind);
            Assert.True(f.TargetIsOrigin);
            Assert.Equal(java, f.Found);
        }
    }

    [Fact]
    public void TheLoaderVersionMeetsARangeWhenItIsKnownAndNeverWhenItIsNot()
    {
        var needs = new[] { ("fabricloader", ">=0.99.0") };
        Assert.Equal(FindingKind.WrongVersion, Assert.Single(Check(Mod("addon", needs: needs)).Findings).Kind);
        // Newest at install time: unknown, so nothing contradicts the range.
        Assert.True(Check(Fabric121 with { LoaderVersion = null }, Mod("addon", needs: needs)).IsClean);
    }

    // ---- WrongMinecraft ----

    [Fact]
    public void AModForAnotherMinecraftIsBlockingAndTerminal()
    {
        var mod = Mod("addon", mc: "1.20.x");
        var report = Check(mod);
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.WrongMinecraft, f.Kind);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Equal("Built for Minecraft 1.20.x, not 1.21.1.", f.Summary);
        Assert.Same(f, report.Terminal(mod));
    }

    [Fact]
    public void AModForThisMinecraftIsFine() => Assert.True(Check(Mod("addon", mc: "1.21.x")).IsClean);

    // ---- WrongLoader / Unreadable / ForeignClient ----

    [Theory]
    [InlineData(ModLoader.Forge, "Forge mod")]
    [InlineData(ModLoader.NeoForge, "NeoForge mod")]
    [InlineData(ModLoader.Quilt, "Quilt-only mod")]
    [InlineData(ModLoader.Unknown, "Not a Fabric mod")]
    public void AJarForAnotherLoaderIsLeftOut(ModLoader loader, string text)
    {
        var report = Check(File(Info("addon", loader: loader)));
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.WrongLoader, f.Kind);
        Assert.Contains(text, f.Summary);
        Assert.True(f.Excludes);
    }

    [Fact]
    public void AnUnreadableJarIsReportedNotSkipped()
    {
        var jar = File(new ModInfo { FileName = "broken.jar", ReadFailed = true });
        var f = Assert.Single(Check(jar).Findings);
        Assert.Equal(FindingKind.Unreadable, f.Kind);
        Assert.Equal("Couldn't read this file.", f.Summary);
    }

    [Fact]
    public void AFabricJarWithoutAnIdIsUnreadable()
    {
        var f = Assert.Single(Check(File(new ModInfo { FileName = "x.jar", Loader = ModLoader.Fabric })).Findings);
        Assert.Equal(FindingKind.Unreadable, f.Kind);
    }

    [Fact]
    public void AnotherClientsCoreJarIsBlockingAndOnlyLikely()
    {
        var f = Assert.Single(Check(File(Info("feather", "1.0.0"))).Findings);
        Assert.Equal(FindingKind.ForeignClient, f.Kind);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Equal(Certainty.Likely, f.Certainty); // a hand-kept table, not metadata
        Assert.Contains("Feather", f.Summary);
    }

    // ---- WrongSide ----

    [Fact]
    public void AClientOnlyModOnAServerIsSkippedByTheLoader()
    {
        var server = Fabric121 with { Side = RunSide.Server };
        var client = File(Info("hud", env: ModEnvironment.Client));
        var dependant = Mod("hud-addon", needs: new[] { ("hud", "*") });
        var report = Check(server, client, dependant);
        var side = Assert.Single(Of(report, FindingKind.WrongSide));
        Assert.Equal(Severity.Warning, side.Severity); // the game still starts
        Assert.Same(client, side.Subject);
        // ...but whatever needs it loses it.
        Assert.Equal(FindingKind.MissingDependency, Assert.Single(Of(report, FindingKind.MissingDependency)).Kind);
    }

    [Fact]
    public void ClientAndEitherSideModsAreFineOnTheClient()
    {
        Assert.True(Check(File(Info("hud", env: ModEnvironment.Client)), File(Info("lib"))).IsClean);
    }

    [Fact]
    public void AServerOnlyModOnTheClientIsSkipped()
    {
        Assert.Equal(FindingKind.WrongSide, Assert.Single(Check(File(Info("srv", env: ModEnvironment.Server))).Findings).Kind);
    }

    // ---- DuplicateId ----

    // Fabric Loader 0.19.3 loads exactly one jar per id (the higher version) and the game starts: a note, never a stop.
    [Fact]
    public void TwoEnabledCopiesOfOneIdAreANoteAndTheHigherVersionLoads()
    {
        var old = Mod("lib", "1.0.0");
        var newer = Mod("lib", "2.0.0");
        var report = Check(old, newer);
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.DuplicateId, f.Kind);
        Assert.Equal(Severity.Info, f.Severity);
        Assert.Equal(Certainty.Confirmed, f.Certainty);
        Assert.True(f.Excludes);              // the older copy does not load
        Assert.Same(old, f.Subject);
        Assert.Same(newer, f.Related);
        Assert.Equal("Older copy of lib: the game loads lib 2.0.0 and ignores this one.", f.Summary);
        Assert.False(report.HasBlocking);
        Assert.True(report.IsClean);
    }

    [Fact]
    public void TwoCopiesOfTheSameVersionAreANoteToo()
    {
        var a = Mod("lib", "1.0.0");
        var b = Mod("lib", "1.0.0");
        var report = Check(a, b);
        var f = Assert.Single(report.Findings);
        Assert.Equal(Severity.Info, f.Severity);
        Assert.Equal("Second copy of lib 1.0.0: the game loads only one of them.", f.Summary);
        Assert.False(report.HasBlocking);
    }

    [Fact]
    public void AModPinnedToTheOlderCopyIsNotBlocked()
    {
        // The loader may pick the older copy to meet the requirement, so this set starts.
        var report = Check(Mod("lib", "1.0.0"), Mod("lib", "2.0.0"), Mod("addon", needs: new[] { ("lib", "1.0.0") }));
        Assert.Empty(Of(report, FindingKind.WrongVersion));
        Assert.False(report.HasBlocking);
    }

    [Fact]
    public void ARequirementNeitherCopyMeetsIsStillBlocking()
    {
        var addon = Mod("addon", needs: new[] { ("lib", ">=3.0") });
        var report = Check(Mod("lib", "1.0.0"), Mod("lib", "2.0.0"), addon);
        var f = Assert.Single(Of(report, FindingKind.WrongVersion));
        Assert.Same(addon, f.Subject);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Equal("2.0.0", f.Found); // the copy that loads is named first
    }

    [Fact]
    public void BreaksAgainstOnlyTheOlderCopyDoesNotFire()
    {
        // The loader avoids the conflict by loading 2.0.0, which this mod does not break.
        var report = Check(Mod("lib", "1.0.0"), Mod("lib", "2.0.0"), Mod("x", breaks: new[] { ("lib", "<2.0") }));
        Assert.Empty(Of(report, FindingKind.Breaks));
        Assert.False(report.HasBlocking);
    }

    [Fact]
    public void BreaksAgainstTheCopyThatLoadsStillFires()
    {
        var report = Check(Mod("lib", "1.0.0"), Mod("lib", "2.0.0"), Mod("x", breaks: new[] { ("lib", ">=2.0") }));
        Assert.Equal(Severity.Blocking, Assert.Single(Of(report, FindingKind.Breaks)).Severity);
    }

    [Fact]
    public void ATurnedOffTwinIsOnlyClutter()
    {
        var on = Mod("lib", "1.0.0");
        var off = Mod("lib", "2.0.0", enabled: false);
        var report = Check(on, off);
        var f = Assert.Single(report.Findings);
        Assert.Same(off, f.Subject);          // the enabled copy wins over a higher disabled one
        Assert.Equal(Severity.Info, f.Severity);
        Assert.False(report.HasBlocking);
    }

    [Fact]
    public void DifferentIdsAreNotDuplicates() => Assert.True(Check(Mod("a"), Mod("b")).IsClean);

    // ---- OriginManagedConflict ----

    [Fact]
    public void AnInstalledCopyOfAnOriginModStopsTheGame()
    {
        var origin = Mod("sodium", "0.6.13", ModRole.Managed);
        var mine = Mod("sodium", "0.5.0");
        var f = Assert.Single(Check(origin, mine).Findings);
        Assert.Equal(FindingKind.OriginManagedConflict, f.Kind);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Same(mine, f.Subject);
        Assert.Same(origin, f.Related);
        Assert.True(f.TargetIsOrigin);
        Assert.Equal("Origin includes sodium 0.6.13.", f.Summary);
    }

    [Fact]
    public void AModThatProvidesWhatOriginShipsConflictsToo()
    {
        var f = Assert.Single(Check(Mod("indium", "1.0", ModRole.Managed), Mod("sodium-fork", provides: new[] { "indium" })).Findings);
        Assert.Equal(FindingKind.OriginManagedConflict, f.Kind);
    }

    [Fact]
    public void AnImportCandidateOnlyWouldConflict()
    {
        var builtIns = BuiltInModSet.Build("1.21.1", null, new[] { new CatalogMod("sodium", "0.6.13") });
        var candidate = Mod("sodium", "0.5.0", ModRole.ImportCandidate);
        var report = Check(Fabric121 with { BuiltIns = builtIns }, candidate);
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.OriginManagedConflict, f.Kind);
        Assert.Equal(Severity.Info, f.Severity);
        Assert.Equal("Origin includes Sodium 0.6.13.", f.Summary);
    }

    [Fact]
    public void ATurnedOffOriginJarShipsNothing()
    {
        Assert.True(Check(Mod("sodium", "0.6.13", ModRole.Managed, enabled: false), Mod("sodium", "0.5.0")).IsClean);
    }

    // ---- KnownIncompatible ----

    [Fact]
    public void AModOnOriginsShortListIsLikelyNotConfirmed()
    {
        var f = Assert.Single(Check(Mod("optifabric", "1.14.3")).Findings);
        Assert.Equal(FindingKind.KnownIncompatible, f.Kind);
        Assert.Equal(Certainty.Likely, f.Certainty);
        Assert.Contains("OptiFabric", f.Summary);
    }

    [Fact]
    public void DistantHorizonsOnlyConflictsWithTheNewSodium()
    {
        var dh = Mod("distanthorizons", "2.3.2");
        Assert.True(Check(dh).IsClean); // no Sodium in the set: no reason
        var report = Check(Mod("sodium", "0.8.12", ModRole.Managed), dh);
        Assert.Equal(FindingKind.KnownIncompatible, Assert.Single(report.Findings).Kind);
        Assert.True(Check(Mod("sodium", "0.8.12", ModRole.Managed), Mod("distanthorizons", "2.3.6")).IsClean);
    }

    // ---- Breaks / Conflicts / Recommends ----

    [Fact]
    public void ABreaksEntryFiresOnlyWhenTheOtherModIsThereInThatRange()
    {
        var declarer = Mod("addon", breaks: new[] { ("lib", "<2.0") });
        var report = Check(declarer, Mod("lib", "1.5.0"));
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.Breaks, f.Kind);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Equal("Conflicts with lib 1.5.0.", f.Summary);
        Assert.Same(declarer, f.Subject);
        Assert.Equal("1.5.0", f.Found);

        Assert.True(Check(declarer, Mod("lib", "2.1.0")).IsClean); // out of the range
        Assert.True(Check(declarer).IsClean);                      // not there at all
    }

    [Fact]
    public void ABreaksAgainstAnOriginModIsFlaggedAsOrigins()
    {
        var f = Assert.Single(Check(Mod("sodium", "0.6.13", ModRole.Managed), Mod("addon", breaks: new[] { ("sodium", "*") })).Findings);
        Assert.Equal(FindingKind.Breaks, f.Kind);
        Assert.True(f.TargetIsOrigin);
    }

    [Fact]
    public void OriginsOwnBreaksFallOnThePlayersMod()
    {
        // Sodium (Origin's) breaks iris <1.8.7: the old Iris is the one that gives way.
        var iris = Mod("iris", "1.8.5");
        var sodium = Mod("sodium", "0.6.13", ModRole.Managed, breaks: new[] { ("iris", "<1.8.7") });
        var f = Assert.Single(Check(sodium, iris).Findings);
        Assert.Equal(FindingKind.Breaks, f.Kind);
        Assert.Same(iris, f.Subject);
        Assert.Same(sodium, f.Related);
        Assert.True(f.TargetIsOrigin);
        Assert.Equal(Severity.Blocking, f.Severity);

        Assert.True(Check(sodium, Mod("iris", "1.8.8")).IsClean);
    }

    [Fact]
    public void OriginsModsAreNeverTheSubjectOfAFinding()
    {
        var report = Check(Mod("sodium", "0.6.13", ModRole.Managed, needs: new[] { ("missing-thing", "*") }));
        Assert.Empty(report.Findings);
    }

    [Fact]
    public void ConflictsAndRecommendsAreAdviceOnly()
    {
        var mod = File(Info("addon", conflicts: new[] { ("other", "*") }, recommends: new[] { ("friend", ">=1") }));
        var report = Check(mod, Mod("other", "3.0"));
        var conflict = Assert.Single(Of(report, FindingKind.Conflicts));
        var rec = Assert.Single(Of(report, FindingKind.MissingRecommended));
        Assert.Equal(Severity.Warning, conflict.Severity);
        Assert.Equal(Severity.Info, rec.Severity);
        Assert.False(conflict.Excludes);
        Assert.False(rec.Excludes);
        Assert.False(report.HasBlocking);
        Assert.Null(report.Terminal(mod)); // it still loads
        Assert.Equal(">=1", rec.Range);
    }

    [Fact]
    public void ARecommendedModThatIsThereIsNotMentioned()
    {
        var report = Check(File(Info("addon", recommends: new[] { ("friend", "*") })), Mod("friend"));
        Assert.True(report.IsClean);
    }

    // ---- graph behaviour ----

    [Fact]
    public void AModLeftOutTakesItsDependantsWithIt()
    {
        var a = Mod("a", needs: new[] { ("absent", "*") });
        var b = Mod("b", needs: new[] { ("a", "*") });
        var c = Mod("c", needs: new[] { ("b", "*") });
        var report = Check(c, b, a);

        var root = Assert.Single(report.For(a));
        Assert.False(root.IsKnockOn);
        var mid = Assert.Single(report.For(b));
        Assert.Equal(FindingKind.MissingDependency, mid.Kind);
        Assert.Equal(new[] { a }, mid.CausedBy);
        var leaf = Assert.Single(report.For(c));
        Assert.Equal(new[] { b }, leaf.CausedBy);
        Assert.Equal("Needs a.", report.ReasonFor(b));
    }

    [Fact]
    public void AModStaysWhenTheOtherThingItNeedsIsStillThere()
    {
        var report = Check(Mod("a", needs: new[] { ("absent", "*") }), Mod("b", needs: new[] { ("lib", "*") }), Mod("lib"));
        Assert.Empty(report.For(report.Entries[1]));
    }

    [Fact]
    public void ModsThatNeedEachOtherAreFineWhenBothArePresent()
    {
        var report = Check(Mod("a", needs: new[] { ("b", "*") }), Mod("b", needs: new[] { ("a", "*") }));
        Assert.True(report.IsClean);
    }

    [Fact]
    public void ACycleWithOneBrokenMemberLeavesTheWholeCycleOut()
    {
        var a = Mod("a", needs: new[] { ("b", "*"), ("absent", "*") });
        var b = Mod("b", needs: new[] { ("a", "*") });
        var report = Check(a, b);
        Assert.NotNull(report.Terminal(a));
        Assert.NotNull(report.Terminal(b));
        Assert.Equal(new[] { a }, report.Terminal(b)!.CausedBy);
    }

    [Fact]
    public void TwoModsThatBreakEachOtherAreBothFlagged()
    {
        var a = Mod("a", breaks: new[] { ("b", "*") });
        var b = Mod("b", breaks: new[] { ("a", "*") });
        var report = Check(a, b);
        Assert.Equal(FindingKind.Breaks, Assert.Single(report.For(a)).Kind);
        Assert.Equal(FindingKind.Breaks, Assert.Single(report.For(b)).Kind);
    }

    [Fact]
    public void AModLeftOutByAnEarlierPhaseTakesItsDependantsWithIt()
    {
        var wrongMc = Mod("lib", mc: "1.12.2");
        var dependant = Mod("addon", needs: new[] { ("lib", "*") });
        var report = Check(wrongMc, dependant);
        var f = Assert.Single(report.For(dependant));
        Assert.Equal(new[] { wrongMc }, f.CausedBy);
    }

    // ---- jar-in-jar ----

    [Fact]
    public void ANestedJarProvidesItsIdToTheModsAroundIt()
    {
        var host = Mod("host", "1.0", nested: new[] { new NestedMod("inner-lib", "2.5.0", "Inner") });
        var user = Mod("user", needs: new[] { ("inner-lib", ">=2") });
        Assert.True(Check(host, user).IsClean);
        // ...at the version the nested jar states
        var tooNew = Mod("user2", needs: new[] { ("inner-lib", ">=3") });
        Assert.Equal(FindingKind.WrongVersion, Assert.Single(Check(host, tooNew).Findings).Kind);
    }

    [Fact]
    public void ANestedJarsOwnProvidesCount()
    {
        var host = Mod("host", nested: new[] { new NestedMod("inner", "1.0.0", null) { Provides = new[] { "inner-compat" } } });
        Assert.True(Check(host, Mod("user", needs: new[] { ("inner-compat", "*") })).IsClean);
    }

    [Fact]
    public void ARemovedHostTakesItsNestedIdsWithIt()
    {
        var host = Mod("host", needs: new[] { ("absent", "*") }, nested: new[] { new NestedMod("inner-lib", "1.0.0", null) });
        var user = Mod("user", needs: new[] { ("inner-lib", "*") });
        var report = Check(host, user);
        Assert.Equal(new[] { host }, Assert.Single(report.For(user)).CausedBy);
    }

    [Fact]
    public void ProvidedIdsStateTheirOwnVersionWhenTheyHaveOne()
    {
        var info = Info("fork", "5.0.0", provides: new[] { "lib" }) with
        {
            ProvidedVersions = new Dictionary<string, string> { ["lib"] = "1.2.0" },
        };
        var fork = File(info);
        Assert.True(Check(fork, Mod("user", needs: new[] { ("lib", "1.x") })).IsClean);
        Assert.Equal(FindingKind.WrongVersion, Assert.Single(Check(fork, Mod("user", needs: new[] { ("lib", ">=5") })).Findings).Kind);
    }

    [Fact]
    public void FabricApiModulesAnswerToABundledFabricApi()
    {
        var builtIns = BuiltInModSet.Build("1.21.1", null, Array.Empty<CatalogMod>());
        var report = Check(Fabric121 with { BuiltIns = builtIns }, Mod("addon", needs: new[] { ("fabric-resource-loader-v0", "*") }));
        Assert.True(report.IsClean);
    }

    // ---- report plumbing ----

    [Fact]
    public void FindingsComeOutInFileNameOrderWhateverTheInputOrder()
    {
        var mods = new[]
        {
            File(Info("zeta", needs: new[] { ("x", "*") }), fileName: "zeta.jar"),
            File(Info("alpha", needs: new[] { ("x", "*") }), fileName: "alpha.jar"),
            File(Info("mid", needs: new[] { ("x", "*") }), fileName: "mid.jar"),
        };
        var a = Check(mods).Findings.Select(f => f.Subject.FileName).ToList();
        var b = Check(mods.Reverse().ToArray()).Findings.Select(f => f.Subject.FileName).ToList();
        Assert.Equal(new[] { "alpha.jar", "mid.jar", "zeta.jar" }, a);
        Assert.Equal(a, b);
    }

    [Fact]
    public void TheEngineDoesNotChangeItsInput()
    {
        var mods = new[] { Mod("a", needs: new[] { ("absent", "*") }), Mod("b") };
        var before = mods.Select(m => (m.Path, m.Enabled, m.FileName)).ToList();
        Check(mods);
        Assert.Equal(before, mods.Select(m => (m.Path, m.Enabled, m.FileName)).ToList());
    }

    // ---- 1.8.9 (legacy Forge) ----

    private static ModFile Forge(string id, string version = "1.0", string? mc = "1.8.9", (string, string)[]? needs = null,
        bool enabled = true) =>
        File(Info(id, version, loader: ModLoader.LegacyForge, needs: needs) with { McVersion = mc }, ModRole.Profile, enabled);

    [Fact]
    public void AForgeModForThisVersionIsFine() => Assert.True(Check(Forge189, Forge("hud")).IsClean);

    [Fact]
    public void ForgeMetadataIsOnlyLikely()
    {
        var f = Assert.Single(Check(Forge189, Forge("hud", mc: "1.7.10")).Findings);
        Assert.Equal(FindingKind.WrongMinecraft, f.Kind);
        Assert.Equal(Certainty.Likely, f.Certainty);   // mcmod.info is advisory
        Assert.Equal(Severity.Warning, f.Severity);
        Assert.Equal("Built for Minecraft 1.7.10, not 1.8.9.", f.Summary);
    }

    [Fact]
    public void AFabricModOn189IsTheWrongLoader()
    {
        var f = Assert.Single(Check(Forge189, File(Info("fab"))).Findings);
        Assert.Equal(FindingKind.WrongLoader, f.Kind);
        Assert.Equal("1.8.9 runs on Forge in Origin, so Fabric mods can't load there.", f.Summary);
    }

    [Fact]
    public void AnUnknownJarOn189IsNotAForgeMod()
    {
        var f = Assert.Single(Check(Forge189, File(Info("x", loader: ModLoader.Unknown))).Findings);
        Assert.Equal("Not a Forge 1.8.9 mod.", f.Summary);
    }

    [Fact]
    public void ADuplicateForgeModIdIsBlockingAndConfirmed()
    {
        var f = Assert.Single(Check(Forge189, Forge("hud", "1.0"), Forge("hud", "1.1")).Findings);
        Assert.Equal(FindingKind.DuplicateId, f.Kind);
        Assert.Equal(Severity.Blocking, f.Severity);
        Assert.Equal(Certainty.Confirmed, f.Certainty);
    }

    [Fact]
    public void AForgeRequiredModThatIsMissingIsOnlyAWarningAndNeverExcludes()
    {
        var mod = Forge("addon", needs: new[] { ("somelib", "*"), ("Forge", "*"), ("FML", "*") });
        var report = Check(Forge189, mod);
        var f = Assert.Single(report.Findings);
        Assert.Equal(FindingKind.MissingDependency, f.Kind);
        Assert.Equal(Certainty.Likely, f.Certainty);
        Assert.Equal(Severity.Warning, f.Severity);
        Assert.False(f.Excludes);
        Assert.Null(report.Terminal(mod));
        Assert.False(report.HasBlocking);
    }

    [Fact]
    public void AForgeRequiredModThatIsThereIsNotMentioned()
    {
        Assert.True(Check(Forge189, Forge("addon", needs: new[] { ("somelib", "*") }), Forge("somelib")).IsClean);
    }

    [Fact]
    public void FabricRulesDoNotApplyOn189()
    {
        // No "Origin includes", no Known list: OptiFine is part of the 1.8.9 stack and must not be flagged.
        var optifine = Forge("optifine", "1.8.9_HD_U_M5");
        Assert.True(Check(Forge189, optifine).IsClean);
    }
}
