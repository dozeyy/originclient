using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Social;
using Xunit;

namespace OriginLauncher.Tests;

// Mod profiles: a named set of the player's own mods for one Minecraft version,
// optionally with a server. Rules and list edits are pure; the folder work
// (launch folder, copy, delete, the 1.8.9 mirror) runs on real files in a temp
// directory.
public class ModProfileTests : IDisposable
{
    private const string IdA = "0a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private const string IdB = "1a1b2c3d4e5f60718293a4b5c6d7e8f9";
    private static readonly DateTime Monday = new(2026, 10, 5, 12, 0, 0, DateTimeKind.Utc);
    private static readonly DateTime Friday = new(2026, 10, 9, 12, 0, 0, DateTimeKind.Utc);

    private readonly string _root = Directory.CreateTempSubdirectory("mod-profiles").FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private string P(string relativePath) =>
        Path.Combine(_root, relativePath.Replace('/', Path.DirectorySeparatorChar));

    private string Write(string relativePath, string text, DateTime? time = null)
    {
        var path = P(relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, text);
        if (time is { } t) File.SetLastWriteTimeUtc(path, t);
        return path;
    }

    private static ModProfile Profile(string id, string name, string version = "1.21.1", string? server = null, string? address = null) =>
        new() { Id = id, Name = name, Version = version, ServerName = server, ServerAddress = address };

    // ---------------------------------------------------------------- ids, versions

    [Fact]
    public void GeneratedIdsAreValidFolderNames()
    {
        var id = ProfileRules.NewId();
        Assert.True(ProfileRules.IsValidId(id));
        Assert.NotEqual(id, ProfileRules.NewId());
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("..")]
    [InlineData("..\\..\\evil")]
    [InlineData("0a1b2c3d4e5f60718293a4b5c6d7e8f")]       // 31 characters
    [InlineData("0A1B2C3D4E5F60718293A4B5C6D7E8F9")]       // upper case is never generated
    [InlineData("0a1b2c3d4e5f60718293a4b5c6d7e8f/")]
    public void OnlyIdsTheLauncherMadeAreUsedAsFolderNames(string? id)
    {
        Assert.False(ProfileRules.IsValidId(id));
    }

    [Theory]
    [InlineData("1.21.1", true)]
    [InlineData("26.1", true)]
    [InlineData("1.8.9", true)]
    [InlineData("", false)]
    [InlineData("..", false)]
    [InlineData(".hidden", false)]
    [InlineData("1..2", false)]
    [InlineData("1.21/../x", false)]
    [InlineData("a b", false)]
    public void VersionsMustBeFolderSafe(string version, bool ok)
    {
        Assert.Equal(ok, ProfileRules.IsValidVersion(version));
    }

    [Fact]
    public void AHandEditedProfileWithABadIdIsNotUsable()
    {
        Assert.True(ProfileRules.IsUsable(Profile(IdA, "Fine")));
        Assert.False(ProfileRules.IsUsable(Profile("../../x", "Bad id")));
        Assert.False(ProfileRules.IsUsable(Profile(IdA, "  ")));
        Assert.False(ProfileRules.IsUsable(null));
    }

    // ---------------------------------------------------------------- names

    [Fact]
    public void NamesAreTrimmedRequiredAndShort()
    {
        Assert.Null(ProfileRules.ValidateName("  Hypixel  ", "1.21.1", []));
        Assert.NotNull(ProfileRules.ValidateName("", "1.21.1", []));
        Assert.NotNull(ProfileRules.ValidateName("   ", "1.21.1", []));
        Assert.NotNull(ProfileRules.ValidateName(null, "1.21.1", []));
        Assert.Null(ProfileRules.ValidateName(new string('a', 32), "1.21.1", []));
        Assert.NotNull(ProfileRules.ValidateName(new string('a', 33), "1.21.1", []));
        Assert.NotNull(ProfileRules.ValidateName("bad\nname", "1.21.1", []));
    }

    [Fact]
    public void NamesAreUniquePerVersionIgnoringCase()
    {
        var existing = new[] { Profile(IdA, "Hypixel", "1.21.1") };

        Assert.NotNull(ProfileRules.ValidateName("hypixel", "1.21.1", existing));
        Assert.NotNull(ProfileRules.ValidateName(" HYPIXEL ", "1.21.1", existing));
        // The same name on another version is a different profile.
        Assert.Null(ProfileRules.ValidateName("Hypixel", "1.20.1", existing));
        // Renaming a profile to its own name is fine.
        Assert.Null(ProfileRules.ValidateName("Hypixel", "1.21.1", existing, exceptId: IdA));
    }

    [Fact]
    public void CopyNamesCountUpAndStayWithinTheLimit()
    {
        var list = new List<ModProfile> { Profile(IdA, "Hypixel") };
        Assert.Equal("Hypixel copy", ProfileRules.UniqueCopyName("Hypixel", "1.21.1", list));

        list.Add(Profile(IdB, "Hypixel copy"));
        Assert.Equal("Hypixel copy 2", ProfileRules.UniqueCopyName("Hypixel", "1.21.1", list));

        var longName = new string('x', 32);
        var copy = ProfileRules.UniqueCopyName(longName, "1.21.1", [Profile(IdA, longName)]);
        Assert.True(copy.Length <= ProfileRules.MaxNameLength);
        Assert.EndsWith(" copy", copy);
        Assert.Null(ProfileRules.ValidateName(copy, "1.21.1", [Profile(IdA, longName)]));
    }

    // ---------------------------------------------------------------- servers

    [Fact]
    public void AServerIsOptionalButAnAddressMustBeJoinable()
    {
        Assert.Null(ProfileRules.ValidateServer(null, null));
        Assert.Null(ProfileRules.ValidateServer("", "  "));
        Assert.Null(ProfileRules.ValidateServer("Hypixel", "mc.hypixel.net"));
        Assert.Null(ProfileRules.ValidateServer(null, "play.example.net:25566"));
        Assert.Null(ProfileRules.ValidateServer(null, "[::1]:25566"));
        // A name alone is useless.
        Assert.NotNull(ProfileRules.ValidateServer("Hypixel", null));
        // Anything that could smuggle an extra game argument is refused.
        Assert.NotNull(ProfileRules.ValidateServer(null, "host --demo"));
        Assert.NotNull(ProfileRules.ValidateServer(null, "host:99999"));
        Assert.NotNull(ProfileRules.ValidateServer(null, "bad host"));
        Assert.NotNull(ProfileRules.ValidateServer(new string('n', 33), "host.net"));
    }

    [Fact]
    public void TheJoinButtonSaysWhereItGoes()
    {
        Assert.Equal("Join Hypixel", ProfileRules.JoinLabel(Profile(IdA, "p", server: "Hypixel", address: "mc.hypixel.net")));
        Assert.Equal("Join mc.hypixel.net", ProfileRules.JoinLabel(Profile(IdA, "p", address: "mc.hypixel.net:25570")));
        Assert.Equal("Join server", ProfileRules.JoinLabel(Profile(IdA, "p")));
        Assert.True(Profile(IdA, "p", address: "x.net").HasServer);
        Assert.False(Profile(IdA, "p", server: "name only").HasServer);
    }

    // Joining reuses the friends feature's argument builder. Spell out what each
    // era gets, because a profile's server is launched through exactly this.
    [Theory]
    [InlineData("1.8.9", "--server|mc.net")]
    [InlineData("1.16.5", "--server|mc.net")]
    [InlineData("1.19.4", "--server|mc.net")]
    [InlineData("1.20", "--quickPlayMultiplayer|mc.net")]
    [InlineData("1.21.1", "--quickPlayMultiplayer|mc.net")]
    [InlineData("26.1", "--quickPlayMultiplayer|mc.net")]
    public void AProfileServerJoinsWithTheArgumentsOfItsEra(string version, string expected)
    {
        Assert.Equal(expected.Split('|'), JoinArguments.Build(version, "mc.net"));
    }

    [Fact]
    public void AProfileServerWithAPortKeepsItOnEveryEra()
    {
        Assert.Equal(["--server", "mc.net", "--port", "25570"], JoinArguments.Build("1.8.9", "mc.net:25570"));
        Assert.Equal(["--quickPlayMultiplayer", "mc.net:25570"], JoinArguments.Build("1.21.1", "mc.net:25570"));
    }

    // ---------------------------------------------------------------- which profile a launch uses

    [Fact]
    public void AProfileOnlyAppliesToItsOwnVersion()
    {
        var list = new[] { Profile(IdA, "SMP", "1.21.1"), Profile(IdB, "PvP", "1.8.9") };

        Assert.Equal(IdA, ProfileRules.Active(list, IdA, "1.21.1")!.Id);
        Assert.Null(ProfileRules.Active(list, IdA, "1.20.1"));
        Assert.Null(ProfileRules.Active(list, null, "1.21.1"));
        Assert.Null(ProfileRules.Active(list, "ffffffffffffffffffffffffffffffff", "1.21.1"));
        Assert.Null(ProfileRules.Active(list, IdA, null));
    }

    [Fact]
    public void OriginOnlyLaunchesIgnoreProfiles()
    {
        var list = new[] { Profile(IdA, "SMP", "1.21.1") };

        Assert.Equal(IdA, ProfileRules.ForLaunch(list, IdA, "1.21.1", externalMods: true)!.Id);
        // "Personal mods" off (the crash window's Origin-only retry) means no player mods at all.
        Assert.Null(ProfileRules.ForLaunch(list, IdA, "1.21.1", externalMods: false));
        Assert.Null(ProfileRules.ForLaunch(list, IdA, "1.20.1", externalMods: true));
    }

    [Fact]
    public void AnUnusableSelectedProfileIsNeverLaunched()
    {
        var list = new[] { Profile("../../x", "Evil", "1.21.1") };
        Assert.Null(ProfileRules.ForLaunch(list, "../../x", "1.21.1", externalMods: true));
    }

    // ---------------------------------------------------------------- paths

    [Fact]
    public void ProfilesLiveInTheVersionsInstanceFolder()
    {
        Assert.Equal(Path.Combine("I", "1.21.1", "profiles"), InstanceLayout.ProfilesDir("I", "1.21.1"));
        Assert.Equal(Path.Combine("I", "1.21.1", "profiles", IdA), InstanceLayout.ProfileDir("I", "1.21.1", IdA));
        Assert.Equal(Path.Combine("I", "1.21.1", "profiles", IdA, "mods"), InstanceLayout.ProfileModsDir("I", "1.21.1", IdA));
    }

    [Fact]
    public void TheProfilesFolderIsSharedNotAccountData()
    {
        // Otherwise the old-layout migration would move it into an account's game folder.
        Assert.True(InstanceLayout.IsSharedEntry("profiles"));
        Assert.True(InstanceLayout.IsSharedEntry("PROFILES"));
        // The per-account launch folders are rebuilt each launch, never shared.
        Assert.False(InstanceLayout.IsSharedEntry(LaunchModFolder.ProfileFolderName));
        Assert.False(InstanceLayout.IsSharedEntry(LaunchModFolder.OriginOnlyFolderName));
    }

    // ---------------------------------------------------------------- the profile list

    [Fact]
    public void CreatingAProfileAddsItAndTrimsWhatWasTyped()
    {
        var list = new List<ModProfile>();
        var error = ProfileBook.Create(list, "  Survival  ", "1.21.1", " SMP ", " play.net ", out var created, () => IdA);

        Assert.Null(error);
        Assert.Same(created, Assert.Single(list));
        Assert.Equal(IdA, created!.Id);
        Assert.Equal("Survival", created.Name);
        Assert.Equal("1.21.1", created.Version);
        Assert.Equal("SMP", created.ServerName);
        Assert.Equal("play.net", created.ServerAddress);
    }

    [Fact]
    public void CreatingWithoutAServerLeavesItOut()
    {
        var list = new List<ModProfile>();
        Assert.Null(ProfileBook.Create(list, "Plain", "1.20.1", "", "  ", out var created));
        Assert.Null(created!.ServerName);
        Assert.Null(created.ServerAddress);
        Assert.False(created.HasServer);
        Assert.True(ProfileRules.IsValidId(created.Id));
    }

    [Fact]
    public void ABadProfileIsRefusedAndTheListIsUntouched()
    {
        var list = new List<ModProfile> { Profile(IdA, "Taken", "1.21.1") };

        Assert.NotNull(ProfileBook.Create(list, "taken", "1.21.1", null, null, out var a));
        Assert.NotNull(ProfileBook.Create(list, "", "1.21.1", null, null, out _));
        Assert.NotNull(ProfileBook.Create(list, "Ok", "../x", null, null, out _));
        Assert.NotNull(ProfileBook.Create(list, "Ok", null, null, null, out _));
        Assert.NotNull(ProfileBook.Create(list, "Ok", "1.21.1", "Name only", null, out _));
        Assert.NotNull(ProfileBook.Create(list, "Ok", "1.21.1", null, "not a server!", out _));
        Assert.Null(a);
        Assert.Single(list);
    }

    [Fact]
    public void EditingRenamesAndChangesTheServerButNeverTheVersion()
    {
        var list = new List<ModProfile> { Profile(IdA, "Old", "1.21.1"), Profile(IdB, "Other", "1.21.1") };

        Assert.Null(ProfileBook.Edit(list, IdA, "New", "Home", "home.net"));
        Assert.Equal("New", list[0].Name);
        Assert.Equal("home.net", list[0].ServerAddress);
        Assert.Equal("1.21.1", list[0].Version);

        // Clearing the server fields removes the server.
        Assert.Null(ProfileBook.Edit(list, IdA, "New", "", ""));
        Assert.False(list[0].HasServer);

        // A clash with another profile of the version, and a missing profile, are refused.
        Assert.NotNull(ProfileBook.Edit(list, IdA, "other", null, null));
        Assert.Equal("New", list[0].Name);
        Assert.NotNull(ProfileBook.Edit(list, "ffffffffffffffffffffffffffffffff", "x", null, null));
    }

    [Fact]
    public void DuplicatingCopiesTheSettingsUnderAFreeNameAndANewId()
    {
        var list = new List<ModProfile> { Profile(IdA, "Hypixel", "1.21.1", "Hypixel", "mc.hypixel.net") };

        Assert.Null(ProfileBook.Duplicate(list, IdA, out var copy, () => IdB));

        Assert.Equal(2, list.Count);
        Assert.Equal(IdB, copy!.Id);
        Assert.Equal("Hypixel copy", copy.Name);
        Assert.Equal("1.21.1", copy.Version);
        Assert.Equal("mc.hypixel.net", copy.ServerAddress);
        Assert.NotNull(ProfileBook.Duplicate(list, "ffffffffffffffffffffffffffffffff", out _));
    }

    [Fact]
    public void RemovingTakesOutOnlyThatProfile()
    {
        var list = new List<ModProfile> { Profile(IdA, "A"), Profile(IdB, "B") };

        Assert.True(ProfileBook.Remove(list, IdA));
        Assert.Equal(IdB, Assert.Single(list).Id);
        Assert.False(ProfileBook.Remove(list, IdA));
    }

    // ---------------------------------------------------------------- the launch mods folder

    private static bool OriginJar(string name) => name.StartsWith("originclient", StringComparison.OrdinalIgnoreCase)
        || name.StartsWith("fabric-api", StringComparison.OrdinalIgnoreCase);

    [Fact]
    public void AProfileLaunchLoadsOriginsJarsPlusTheProfilesOwn()
    {
        var shared = new[]
        {
            Write("shared/originclient.jar", "o"),
            Write("shared/fabric-api.jar", "f"),
            Write("shared/loose-mod.jar", "the player's normal-set mod"),   // NOT in a profile launch
            Write("shared/origin-off.jar.disabled", "off"),
        };
        var profile = new[]
        {
            Write("profile/sodium-extra.jar", "s"),
            Write("profile/turned-off.jar.disabled", "off"),
            Write("profile/fabric-api-copy.jar", "a second Fabric API would stop boot"),
        };

        var set = LaunchModFolder.LaunchSet(shared, profile, OriginJar);

        Assert.Equal(
            ["fabric-api.jar", "originclient.jar", "sodium-extra.jar"],
            set.Keys.OrderBy(k => k, StringComparer.Ordinal));
        Assert.Equal(shared[0], set["originclient.jar"]);
        Assert.Equal(profile[0], set["sodium-extra.jar"]);
    }

    [Fact]
    public void AnOriginOnlyLaunchHasNoProfileJars()
    {
        var shared = new[] { Write("shared/originclient.jar", "o"), Write("shared/loose-mod.jar", "x") };

        var set = LaunchModFolder.LaunchSet(shared, [], OriginJar);

        Assert.Equal(["originclient.jar"], set.Keys.ToList());
    }

    [Fact]
    public void TheLaunchFolderIsBuiltToMatchAndStaleJarsLeave()
    {
        Write("launch/old-profile-mod.jar", "from the last profile launched");
        Write("launch/readme.txt", "not ours either");
        var desired = new Dictionary<string, string>
        {
            ["originclient.jar"] = Write("shared/originclient.jar", "o1", Monday),
            ["sodium-extra.jar"] = Write("profile/sodium-extra.jar", "s1", Monday),
        };

        LaunchModFolder.Sync(P("launch"), desired);

        Assert.Equal(["originclient.jar", "sodium-extra.jar"],
            Directory.GetFiles(P("launch")).Select(Path.GetFileName).OrderBy(n => n, StringComparer.Ordinal));
        Assert.Equal("o1", File.ReadAllText(P("launch/originclient.jar")));
        // The sources are only read.
        Assert.Equal("s1", File.ReadAllText(P("profile/sodium-extra.jar")));
    }

    [Fact]
    public void AnUnchangedLaunchFolderIsLeftAloneAndAChangedJarIsReplaced()
    {
        var source = Write("shared/originclient.jar", "v1", Monday);
        var desired = new Dictionary<string, string> { ["originclient.jar"] = source };
        LaunchModFolder.Sync(P("launch"), desired);
        var firstWrite = File.GetLastWriteTimeUtc(P("launch/originclient.jar"));
        Assert.Equal(Monday, firstWrite);

        // Same bytes, same stamp: no copy is made (the stamp survives).
        LaunchModFolder.Sync(P("launch"), desired);
        Assert.Equal(Monday, File.GetLastWriteTimeUtc(P("launch/originclient.jar")));

        // A launcher update changes the source: the copy follows, and no temp file is left.
        Write("shared/originclient.jar", "version two", Friday);
        LaunchModFolder.Sync(P("launch"), desired);
        Assert.Equal("version two", File.ReadAllText(P("launch/originclient.jar")));
        Assert.Empty(Directory.GetFiles(P("launch"), "*.download"));
    }

    // ---------------------------------------------------------------- profile folders on disk

    [Fact]
    public void DuplicatingCopiesEveryModIncludingTurnedOffOnes()
    {
        Write("a/mods/one.jar", "1", Monday);
        Write("a/mods/two.jar.disabled", "2");
        Write("a/mods/config/two.json", "{}");

        Assert.Null(ProfileFolders.CopyMods(P("a/mods"), P("b/mods")));

        Assert.Equal("1", File.ReadAllText(P("b/mods/one.jar")));
        Assert.Equal(Monday, File.GetLastWriteTimeUtc(P("b/mods/one.jar")));
        Assert.Equal("2", File.ReadAllText(P("b/mods/two.jar.disabled")));
        Assert.Equal("{}", File.ReadAllText(P("b/mods/config/two.json")));
        // The original is untouched, and the copy leaves no temp files.
        Assert.Equal("1", File.ReadAllText(P("a/mods/one.jar")));
        Assert.Empty(Directory.GetFiles(P("b/mods"), "*.download", SearchOption.AllDirectories));
    }

    [Fact]
    public void DuplicatingAnEmptyOrNeverOpenedProfileGivesAnEmptyOne()
    {
        Assert.Null(ProfileFolders.CopyMods(P("never/mods"), P("copy/mods")));
        Assert.True(Directory.Exists(P("copy/mods")));
        Assert.Empty(Directory.GetFileSystemEntries(P("copy/mods")));
    }

    [Fact]
    public void ModsAreCountedOnOrOffButNotOtherFiles()
    {
        Write("p/mods/a.jar", "a");
        Write("p/mods/b.JAR", "b");
        Write("p/mods/c.jar.disabled", "c");
        Write("p/mods/notes.txt", "n");
        Write("p/mods/d.jar.download", "half");

        Assert.Equal(3, ProfileFolders.CountMods(P("p/mods")));
        Assert.Equal(0, ProfileFolders.CountMods(P("missing/mods")));
    }

    [Fact]
    public void DeletingRemovesTheWholeProfileFolderAndOnlyThat()
    {
        Write("profiles/" + IdA + "/mods/one.jar", "1");
        Write("profiles/" + IdB + "/mods/two.jar", "2");

        Assert.Null(ProfileFolders.Delete(P("profiles/" + IdA)));

        Assert.False(Directory.Exists(P("profiles/" + IdA)));
        Assert.Equal("2", File.ReadAllText(P("profiles/" + IdB + "/mods/two.jar")));
        Assert.DoesNotContain(Directory.GetDirectories(P("profiles")), d => d.EndsWith(ProfileFolders.ParkedSuffix));
    }

    [Fact]
    public void DeletingAProfileThatWasNeverOpenedIsFine()
    {
        Assert.Null(ProfileFolders.Delete(P("profiles/" + IdA)));
    }

    [Fact]
    public void AParkedFolderLeftBehindIsSweptNextTime()
    {
        Write("profiles/" + IdA + ProfileFolders.ParkedSuffix + "/mods/one.jar", "1");
        Write("profiles/" + IdB + "/mods/two.jar", "2");

        ProfileFolders.SweepParked(P("profiles"));

        Assert.False(Directory.Exists(P("profiles/" + IdA + ProfileFolders.ParkedSuffix)));
        Assert.True(File.Exists(P("profiles/" + IdB + "/mods/two.jar")));
    }

    // ---------------------------------------------------------------- 1.8.9 (Forge) mirror with a profile

    private string MirrorDir => P("1.8.9/accounts/steve/mods");

    [Fact]
    public void AForgeProfileLaunchMirrorsOriginsJarsAndTheProfilesModsOnly()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        Write("1.8.9/mods/loose-mod.jar", "normal set", Monday);
        Write("1.8.9/profiles/" + IdA + "/mods/pvp-mod.jar", "profile", Monday);
        Write("1.8.9/profiles/" + IdA + "/mods/pvp-off.jar.disabled", "off");

        Assert.True(SharedMods.Mirror(P("1.8.9/mods"), MirrorDir, n => OriginJar(n),
            profileMods: P("1.8.9/profiles/" + IdA + "/mods")));

        Assert.Equal(["originclient.jar", "pvp-mod.jar"],
            Directory.GetFiles(MirrorDir).Select(Path.GetFileName).Where(n => !n!.StartsWith('.'))
                .OrderBy(n => n, StringComparer.Ordinal));
    }

    [Fact]
    public void SwitchingProfileOrBackToNormalChangesTheMirror()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        Write("1.8.9/mods/loose-mod.jar", "normal set", Monday);
        Write("1.8.9/profiles/" + IdA + "/mods/pvp-mod.jar", "profile", Monday);
        var profileMods = P("1.8.9/profiles/" + IdA + "/mods");

        Assert.True(SharedMods.Mirror(P("1.8.9/mods"), MirrorDir, OriginJar, profileMods: profileMods));
        Assert.True(File.Exists(Path.Combine(MirrorDir, "pvp-mod.jar")));
        Assert.False(File.Exists(Path.Combine(MirrorDir, "loose-mod.jar")));

        // Plain launch again: the profile's mod goes, the normal set returns.
        Assert.True(SharedMods.Mirror(P("1.8.9/mods"), MirrorDir));
        Assert.False(File.Exists(Path.Combine(MirrorDir, "pvp-mod.jar")));
        Assert.True(File.Exists(Path.Combine(MirrorDir, "loose-mod.jar")));
    }

    [Fact]
    public void AModAddedInGameDuringAProfileLaunchJoinsTheProfileNotTheNormalSet()
    {
        Write("1.8.9/mods/originclient.jar", "origin", Monday);
        var profileMods = P("1.8.9/profiles/" + IdA + "/mods");
        Assert.True(SharedMods.Mirror(P("1.8.9/mods"), MirrorDir, OriginJar, profileMods: profileMods));

        File.WriteAllText(Path.Combine(MirrorDir, "dropped-in.jar"), "player put this in the mirror");
        Assert.True(SharedMods.Mirror(P("1.8.9/mods"), MirrorDir, OriginJar, profileMods: profileMods));

        Assert.Equal("player put this in the mirror", File.ReadAllText(Path.Combine(profileMods, "dropped-in.jar")));
        Assert.False(File.Exists(P("1.8.9/mods/dropped-in.jar")));
    }
}
