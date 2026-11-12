using System;
using System.IO;
using System.Linq;
using System.Threading;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// What uninstall may delete (UninstallPlan) and the record install leaves for it (InstallManifest,
// written by Installer.Commit). Bug run 3, IN-1/IN-2: uninstall once deleted the data folder (worlds)
// with "Also delete" off, and any folder holding an uninstall.exe or named "Origin".
[Collection("download")]
public class UninstallTests
{
    // Commit refuses while Origin Launcher is open; the PC running the tests may well have its own launcher open.
    public UninstallTests() => Installer.LauncherRunning = () => false;

    private static string Temp() => TestKit.Temp("uninst");

    private static void Put(string dir, string rel, string text = "x")
    {
        var p = Path.Combine(dir, rel.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(p)!);
        File.WriteAllText(p, text);
    }

    // A real install through Setup's own commit: stage a launcher zip, move it into place.
    private static void Install(string dir, bool createdFolder)
    {
        var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client());
        Installer.Commit(staged, dir, createdFolder);
    }

    [Fact]
    public void AFolderSetupCreatedIsRemovedWholeIncludingSelfUpdateFiles()
    {
        var dir = Path.Combine(Temp(), "Games", "Origin");
        Install(dir, createdFolder: !Directory.Exists(dir));
        var manifest = InstallManifest.Read(dir)!;
        Assert.True(manifest.OwnsFolder);
        Assert.Contains("Bundled/OriginClient/originclient-1.21.1.jar", manifest.Files);
        Put(dir, "NewInThisUpdate.dll"); // what the launcher's self-update adds later

        var plan = UninstallPlan.For(dir, Temp());
        Assert.Equal(UninstallScope.WholeFolder, plan.Scope);
        Assert.True(plan.Execute());
        Assert.False(Directory.Exists(dir));
    }

    [Fact]
    public void AFolderThatAlreadyExistedLosesOnlyOriginsFiles()
    {
        var dir = Temp(); // existed (empty) before Setup ran
        Install(dir, createdFolder: false);
        Put(dir, "my-notes.txt", "keep me");
        Put(dir, "Bundled/OriginClient/my-own.jar", "keep me too");

        var plan = UninstallPlan.For(dir, Temp());
        Assert.Equal(UninstallScope.ListedFiles, plan.Scope);
        Assert.True(plan.Execute());
        Assert.Equal("keep me", File.ReadAllText(Path.Combine(dir, "my-notes.txt")));
        Assert.Equal("keep me too", File.ReadAllText(Path.Combine(dir, "Bundled", "OriginClient", "my-own.jar")));
        Assert.False(File.Exists(Path.Combine(dir, "OriginLauncher.App.exe")));
        Assert.False(File.Exists(Path.Combine(dir, InstallManifest.FileName)));
        Assert.False(Directory.Exists(Path.Combine(dir, "ThirdParty")), "a folder left empty is removed");
    }

    // UPG-7: files a launcher self-update added (a new bundled version's jar) are in Setup's record too, so uninstall
    // from a folder the player picked removes them; the player's own files stay.
    [Fact]
    public void FilesASelfUpdateAddedAreRemovedFromAPickedFolder()
    {
        var dir = Temp();
        Install(dir, createdFolder: false);
        Put(dir, "my-notes.txt", "keep me");

        var stage = Temp(); // the release the launcher swaps in: what Setup placed plus new files
        Put(stage, "OriginLauncher.App.exe");
        Put(stage, "Bundled/OriginClient/originclient-26.4.jar");
        Put(stage, "runtimes/new.dll");
        Assert.True(OriginLauncher.App.Core.Install.SetupInstallRecord.AddRelease(dir, stage));
        Put(dir, "Bundled/OriginClient/originclient-26.4.jar");
        Put(dir, "runtimes/new.dll");

        var manifest = InstallManifest.Read(dir)!;
        Assert.False(manifest.OwnsFolder);
        Assert.Contains("Bundled/OriginClient/originclient-26.4.jar", manifest.Files);
        Assert.Single(manifest.Files, f => f == "OriginLauncher.App.exe");

        var plan = UninstallPlan.For(dir, Temp());
        Assert.Equal(UninstallScope.ListedFiles, plan.Scope);
        Assert.True(plan.Execute());
        Assert.False(File.Exists(Path.Combine(dir, "Bundled", "OriginClient", "originclient-26.4.jar")));
        Assert.False(Directory.Exists(Path.Combine(dir, "runtimes")));
        Assert.Equal("keep me", File.ReadAllText(Path.Combine(dir, "my-notes.txt")));
    }

    [Fact]
    public void ASelfUpdateLeavesAnOwnedOrUnrecordedFolderAlone()
    {
        var stage = Temp();
        Put(stage, "new.dll");
        var none = Temp();
        Assert.False(OriginLauncher.App.Core.Install.SetupInstallRecord.AddRelease(none, stage));
        Assert.False(File.Exists(Path.Combine(none, InstallManifest.FileName)));

        var owned = Temp();
        new InstallManifest(true, new[] { "OriginLauncher.App.exe" }).Write(owned);
        var before = File.ReadAllText(Path.Combine(owned, InstallManifest.FileName));
        Assert.False(OriginLauncher.App.Core.Install.SetupInstallRecord.AddRelease(owned, stage));
        Assert.Equal(before, File.ReadAllText(Path.Combine(owned, InstallManifest.FileName)));

        // A path that would leave the folder is never written.
        var listed = Temp();
        new InstallManifest(false, new[] { "a.dll" }).Write(listed);
        Assert.True(OriginLauncher.App.Core.Install.SetupInstallRecord.Add(listed, new[] { "../evil.dll", "C:/x.dll", "sub/ok.dll" }));
        Assert.Equal(new[] { "a.dll", "sub/ok.dll" }, InstallManifest.Read(listed)!.Files);
    }

    [Fact]
    public void TheDataFolderIsNeverRemovedWithAlsoDeleteOff()
    {
        // An older Setup let the player install into %LocalAppData%\OriginLauncher itself.
        var data = Path.Combine(Temp(), "OriginLauncher");
        Put(data, "OriginLauncher.App.exe");
        Put(data, "instances/1.21.1/accounts/me/saves/World/level.dat", "my world");
        var plan = UninstallPlan.For(data, data);
        Assert.Equal(UninstallScope.Nothing, plan.Scope);
        Assert.True(plan.Execute());
        Assert.Equal("my world", File.ReadAllText(Path.Combine(data, "instances", "1.21.1", "accounts", "me", "saves", "World", "level.dat")));

        // Even a record claiming the folder only ever removes the files it lists.
        new InstallManifest(true, new[] { "OriginLauncher.App.exe" }).Write(data);
        plan = UninstallPlan.For(data, data);
        Assert.Equal(UninstallScope.ListedFiles, plan.Scope);
        Assert.True(plan.Execute());
        Assert.False(File.Exists(Path.Combine(data, "OriginLauncher.App.exe")));
        Assert.True(File.Exists(Path.Combine(data, "instances", "1.21.1", "accounts", "me", "saves", "World", "level.dat")));

        // And a folder that holds the data folder is never removed either.
        var around = Path.GetDirectoryName(data)!;
        Put(around, "OriginLauncher.App.exe");
        Assert.Equal(UninstallScope.Nothing, UninstallPlan.For(around, data).Scope);
    }

    [Fact]
    public void AnotherProgramsFolderIsLeftAlone()
    {
        // (a) a game whose NSIS uninstaller is also called uninstall.exe; (c) a downloads folder.
        var game = Temp();
        Put(game, "uninstall.exe", "theirs");
        Put(game, "game.exe", "theirs");
        Assert.Equal(UninstallScope.Nothing, UninstallPlan.For(game, Temp()).Scope);
        Assert.True(UninstallPlan.For(game, Temp()).Execute());
        Assert.True(File.Exists(Path.Combine(game, "game.exe")));

        // (b) EA's D:\Origin that an older Setup installed into as-is (its name matched): no record, not Setup's own folder.
        var ea = Path.Combine(Temp(), "Origin");
        Put(ea, "OriginLauncher.App.exe");
        Put(ea, "Origin.exe", "EA");
        Assert.Equal(UninstallScope.Nothing, UninstallPlan.For(ea, Temp()).Scope);
    }

    [Fact]
    public void AnInstallFromBeforeTheRecordIsRemovedOnlyFromItsOwnFolder()
    {
        var own = Path.Combine(Temp(), "OriginLauncher");
        Put(own, "OriginLauncher.App.exe");
        Put(own, "uninstall.exe");
        Assert.Equal(UninstallScope.WholeFolder, UninstallPlan.For(own, Temp()).Scope);

        // An update through Setup keeps it Origin's own and starts the record.
        Install(own, createdFolder: false);
        Assert.True(InstallManifest.Read(own)!.OwnsFolder);

        // Without the launcher in it, a folder named OriginLauncher proves nothing.
        var named = Path.Combine(Temp(), "OriginLauncher");
        Put(named, "notes.txt");
        Assert.Equal(UninstallScope.Nothing, UninstallPlan.For(named, Temp()).Scope);
    }

    [Fact]
    public void AnUpdateKeepsTheRecordAndAddsItsFiles()
    {
        var dir = Temp();
        new InstallManifest(false, new[] { "OldOnly.dll", "OriginLauncher.App.exe" }).Write(dir);
        Put(dir, "OldOnly.dll");
        Put(dir, "OriginLauncher.App.exe");
        Install(dir, createdFolder: false);
        var manifest = InstallManifest.Read(dir)!;
        Assert.False(manifest.OwnsFolder);
        Assert.Contains("OldOnly.dll", manifest.Files);
        Assert.Contains("ThirdParty/Monocraft-LICENSE.txt", manifest.Files);
        Assert.Equal(manifest.Files.Count, manifest.Files.Distinct(StringComparer.OrdinalIgnoreCase).Count());
    }

    [Fact]
    public void AnEditedRecordCanNeverPointOutsideTheFolder()
    {
        var root = Temp();
        var dir = Path.Combine(root, "Install");
        Put(root, "outside.txt", "keep me");
        Put(dir, "OriginLauncher.App.exe");
        new InstallManifest(false, new[] { "../outside.txt", Path.Combine(root, "outside.txt"), "/etc/hosts", "", "OriginLauncher.App.exe" }).Write(dir);
        var plan = UninstallPlan.For(dir, Temp());
        Assert.Equal(new[] { Path.Combine(dir, "OriginLauncher.App.exe") }, plan.Files);
        plan.Execute();
        Assert.Equal("keep me", File.ReadAllText(Path.Combine(root, "outside.txt")));
        Assert.Null(InstallManifest.PathIn(dir, ".."));
        Assert.Null(InstallManifest.PathIn(dir, "a/../../outside.txt"));
    }

    [Fact]
    public void ADamagedRecordIsNoRecord()
    {
        var dir = Path.Combine(Temp(), "Stuff");
        Put(dir, InstallManifest.FileName, "{ not json");
        Assert.Null(InstallManifest.Read(dir));
        Assert.Equal(UninstallScope.Nothing, UninstallPlan.For(dir, Temp()).Scope);
    }

    [Fact]
    public void WellKnownFoldersAreNeverOrigins()
    {
        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        Assert.True(InstallLocation.IsWellKnownFolder(profile));
        Assert.Equal(UninstallScope.Nothing, UninstallPlan.For(profile, Temp()).Scope); // only decides; never executed here
        Assert.True(InstallLocation.IsWellKnownFolder(Path.GetPathRoot(Temp())!));
        Assert.False(InstallLocation.IsWellKnownFolder(Temp()));
        foreach (var personal in new[] { Environment.SpecialFolder.MyPictures, Environment.SpecialFolder.MyMusic, Environment.SpecialFolder.MyVideos })
        {
            var path = Environment.GetFolderPath(personal);
            if (path.Length > 0) Assert.True(InstallLocation.IsWellKnownFolder(path), path);
        }
        Assert.True(InstallLocation.IsWellKnownFolder(Path.GetDirectoryName(profile)!)); // C:\Users
    }

    // A folder link inside the install folder points somewhere that is not Origin's: removing the install
    // removes the link, and nothing behind it is touched (not even a file's read-only mark).
    [Fact]
    public void DeletingAFolderNeverReachesThroughAFolderLink()
    {
        if (!System.Runtime.InteropServices.RuntimeInformation.IsOSPlatform(System.Runtime.InteropServices.OSPlatform.Windows)) return; // Windows-only: mklink /J junctions
        var outside = Temp();
        Put(outside, "keep.txt");
        var keep = Path.Combine(outside, "keep.txt");
        File.SetAttributes(keep, FileAttributes.ReadOnly);
        var dir = Path.Combine(Temp(), "Origin");
        Put(dir, "own.txt");
        var own = Path.Combine(dir, "own.txt");
        File.SetAttributes(own, FileAttributes.ReadOnly);
        var link = Path.Combine(dir, "link");
        using (var mk = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                   Path.Combine(Environment.SystemDirectory, "cmd.exe"), $"/c mklink /J \"{link}\" \"{outside}\"")
               { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true })!)
            mk.WaitForExit();
        Assert.True(Directory.Exists(link), "the test could not make a folder link");
        try
        {
            FileOps.DeleteTree(dir);
            Assert.False(Directory.Exists(dir));
            Assert.True(File.Exists(keep));
            Assert.True((File.GetAttributes(keep) & FileAttributes.ReadOnly) != 0, "a file outside the install was changed");
        }
        finally { File.SetAttributes(keep, FileAttributes.Normal); }
    }

    [Fact]
    public void AFileThatCantBeReplacedPutsTheOldInstallBack()
    {
        // IN-7: the launcher opened meanwhile or antivirus holds a file; here a folder sits where a new file must go.
        var dir = Temp();
        Put(dir, "OriginLauncher.App.exe", "old launcher");
        Put(dir, "Bundled/OriginClient/originclient-1.21.1.jar", "old jar");
        new InstallManifest(false, new[] { "OriginLauncher.App.exe", "Bundled/OriginClient/originclient-1.21.1.jar" }).Write(dir);
        Directory.CreateDirectory(Path.Combine(dir, "ThirdParty", "Monocraft-LICENSE.txt"));

        var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client());
        var ex = Assert.ThrowsAny<Exception>(() => Installer.Commit(staged, dir, createdFolder: false));
        Assert.True(ex is IOException || ex is UnauthorizedAccessException, ex.ToString());

        Assert.Equal("old launcher", File.ReadAllText(Path.Combine(dir, "OriginLauncher.App.exe")));
        Assert.Equal("old jar", File.ReadAllText(Path.Combine(dir, "Bundled", "OriginClient", "originclient-1.21.1.jar")));
        Assert.False(File.Exists(Path.Combine(dir, "Bundled", "OriginClient", "originclient-1.20.jar")), "no new-only file stays");
        Assert.False(Directory.Exists(Path.Combine(dir, "_setup-staging")), "everything went back, so staging is cleared");
        Assert.Equal(2, InstallManifest.Read(dir)!.Files.Count); // the record is unchanged
    }

    [Fact]
    public void TheMoveIsRefusedWhileTheLauncherIsOpen()
    {
        // Opened during the download (an old desktop shortcut): nothing may be replaced under it.
        var dir = Temp();
        Put(dir, "OriginLauncher.App.exe", "old launcher");
        var staged = Installer.Stage(dir, TestKit.Zip(TestKit.MakeLauncherTree()), null, _ => { }, CancellationToken.None, new FakeServer().Client());
        Installer.LauncherRunning = () => true;
        try
        {
            var ex = Assert.Throws<SetupException>(() => Installer.Commit(staged, dir, createdFolder: false));
            Assert.Contains("still open", ex.Message);
        }
        finally { Installer.LauncherRunning = () => false; }
        Assert.Equal("old launcher", File.ReadAllText(Path.Combine(dir, "OriginLauncher.App.exe")));
        Assert.False(Directory.Exists(Path.Combine(dir, "_setup-staging")));
    }

    [Fact]
    public void SilentInstallsLeaveStartWithWindowsAloneByDefault() =>
        Assert.Null(new InstallOptions().StartWithWindows);
}
