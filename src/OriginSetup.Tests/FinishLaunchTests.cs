using System.IO;
using System.Linq;
using System.Xml.Linq;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// The finish page's "Import from another Minecraft launcher" switch: off by default, and the launcher gets
// "--import" (and nothing else) only when it is on. The window itself needs WPF, so these test the rule
// (Engine/FinishLaunch), the XAML default, and that the window's button goes through the rule.
public class FinishLaunchTests
{
    private static string SetupFile(params string[] parts) =>
        Path.Combine(new[] { TestKit.Repo, "src", "OriginSetup" }.Concat(parts).ToArray());

    [Fact]
    public void ImportSwitchIsOffByDefaultInTheRule()
    {
        Assert.False(FinishLaunch.ImportOnByDefault);
    }

    [Fact]
    public void ImportSwitchIsNeverCheckedInTheXaml()
    {
        var xaml = XDocument.Load(SetupFile("UI", "SetupWindow.xaml"));
        XNamespace x = "http://schemas.microsoft.com/winfx/2006/xaml";
        var toggle = xaml.Descendants().Single(e => (string?)e.Attribute(x + "Name") == "ImportToggle");
        var isChecked = (string?)toggle.Attribute("IsChecked");
        Assert.True(isChecked == null || isChecked == "False", "ImportToggle must start unchecked, was IsChecked=" + isChecked);
    }

    [Fact]
    public void CodeBehindNeverTurnsTheImportSwitchOn()
    {
        var code = File.ReadAllText(SetupFile("UI", "SetupWindow.xaml.cs"));
        Assert.DoesNotContain("ImportToggle.IsChecked = true", code);
        Assert.DoesNotContain("ImportToggle.IsChecked=true", code);
    }

    [Fact]
    public void LaunchPassesExactlyImportOnlyWhenTheSwitchIsOn()
    {
        var on = FinishLaunch.StartInfo(@"C:\Origin\OriginLauncher.App.exe", @"C:\Origin", importChecked: true);
        Assert.Equal("--import", on.Arguments);
        Assert.Equal("--import", FinishLaunch.ImportArgument);
        Assert.Equal(@"C:\Origin\OriginLauncher.App.exe", on.FileName);
        Assert.Equal(@"C:\Origin", on.WorkingDirectory);
        Assert.True(on.UseShellExecute);
    }

    [Fact]
    public void LaunchIsUnchangedWhenTheSwitchIsOff()
    {
        var off = FinishLaunch.StartInfo(@"C:\Origin\OriginLauncher.App.exe", @"C:\Origin", importChecked: false);
        Assert.Equal("", off.Arguments);
        Assert.Equal(@"C:\Origin\OriginLauncher.App.exe", off.FileName);
        Assert.Equal(@"C:\Origin", off.WorkingDirectory);
        Assert.True(off.UseShellExecute);
    }

    [Fact]
    public void TheLaunchButtonGoesThroughTheRuleAndHardCodesNoFlag()
    {
        var code = File.ReadAllText(SetupFile("UI", "SetupWindow.xaml.cs"));
        Assert.Contains("FinishLaunch.StartInfo(exe, dir, ImportToggle.IsChecked == true)", code);
        Assert.DoesNotContain("\"--import\"", code);
    }

    [Fact]
    public void TheLauncherReadsTheSameFlagTheInstallerSends()
    {
        var app = File.ReadAllText(Path.Combine(TestKit.Repo, "src", "OriginLauncher.App", "App.xaml.cs"));
        Assert.Contains("\"" + FinishLaunch.ImportArgument + "\"", app);
    }
}
