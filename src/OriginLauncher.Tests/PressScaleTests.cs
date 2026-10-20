using System.Text.RegularExpressions;
using System.Xml.Linq;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// v1.0.60 crash: "Cannot resolve all property references in the property path 'RenderTransform.ScaleX'" on
/// pressing Play. Button styles animated <c>RenderTransform.ScaleX</c> on the button itself, which only
/// resolves while the button's RenderTransform is a ScaleTransform; the Home intro gives PLAY a
/// TranslateTransform, so every later press threw. Press-scale now lives in UI/Press.cs.
///
/// The rule, read from every launcher XAML file (this project runs without WPF): a storyboard that animates
/// through RenderTransform must name its target (Storyboard.TargetName), and that named element must declare
/// its own RenderTransform of the type the path animates. A storyboard without a TargetName animates the
/// styled control itself, whose RenderTransform any page or code can replace, so it is never allowed.
/// </summary>
public class PressScaleTests
{
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";
    private static readonly string AppDir = Path.Combine(TestRepo.Root, "src", "OriginLauncher.App");

    private static IEnumerable<string> XamlFiles() =>
        Directory.EnumerateFiles(AppDir, "*.xaml", SearchOption.AllDirectories)
            .Where(f => !f.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")
                        && !f.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}"));

    // The transform type a path's last property belongs to: "RenderTransform.ScaleX" and
    // "(UIElement.RenderTransform).(TransformGroup.Children)[0].(ScaleTransform.ScaleX)" both need a ScaleTransform.
    private static string? TransformFor(string property) => property switch
    {
        "ScaleX" or "ScaleY" => "ScaleTransform",
        "X" or "Y" => "TranslateTransform",
        "Angle" => "RotateTransform",
        "AngleX" or "AngleY" => "SkewTransform",
        _ => null,
    };

    /// <summary>Every problem found in one XAML document (file:line + why).</summary>
    internal static List<string> Check(string name, XDocument doc)
    {
        var problems = new List<string>();
        var named = doc.Descendants()
            .Select(e => (Element: e, Name: (string?)e.Attribute(X + "Name") ?? (string?)e.Attribute("Name")))
            .Where(p => p.Name is not null)
            .GroupBy(p => p.Name!)
            .ToDictionary(g => g.Key, g => g.Select(p => p.Element).ToList());

        foreach (var anim in doc.Descendants().Where(e => e.Attribute("Storyboard.TargetProperty") is not null))
        {
            var path = (string)anim.Attribute("Storyboard.TargetProperty")!;
            if (!path.Contains("RenderTransform")) continue;
            var where = $"{name}:{((System.Xml.IXmlLineInfo)anim).LineNumber} '{path}'";

            var target = (string?)anim.Attribute("Storyboard.TargetName");
            if (target is null)
            {
                problems.Add($"{where}: animates the control's own RenderTransform (no Storyboard.TargetName). " +
                             "Use ui:Press for press-scale, or name an element that owns its transform.");
                continue;
            }

            var leaf = Regex.Match(path, @"(\w+)\)?$").Groups[1].Value;
            var wantType = TransformFor(leaf);
            var index = Regex.Match(path, @"Children\)?\[(\d+)\]");

            // A name can repeat across templates in one file; any matching declaration is enough.
            var ok = named.TryGetValue(target, out var candidates) && candidates.Any(el =>
            {
                var holder = el.Elements().FirstOrDefault(c => c.Name.LocalName.EndsWith(".RenderTransform"));
                var transform = holder?.Elements().FirstOrDefault();
                if (transform is null) return false;
                if (index.Success)
                {
                    if (transform.Name.LocalName != "TransformGroup") return false;
                    var children = (transform.Elements().FirstOrDefault(c => c.Name.LocalName == "TransformGroup.Children") ?? transform)
                        .Elements().Where(c => !c.Name.LocalName.Contains('.')).ToList();
                    var i = int.Parse(index.Groups[1].Value);
                    transform = i < children.Count ? children[i] : null;
                    if (transform is null) return false;
                }
                return wantType is null || transform.Name.LocalName == wantType;
            });
            if (!ok)
                problems.Add($"{where}: target '{target}' does not declare a <*.RenderTransform> holding a {wantType ?? "transform"} " +
                             "at that path, so the storyboard throws when it begins.");
        }
        return problems;
    }

    [Fact]
    public void Every_RenderTransform_storyboard_targets_an_element_that_owns_the_transform()
    {
        var problems = XamlFiles()
            .SelectMany(f => Check(Path.GetRelativePath(AppDir, f), XDocument.Load(f, LoadOptions.SetLineInfo)))
            .ToList();
        Assert.True(problems.Count == 0, string.Join(Environment.NewLine, problems));
    }

    [Fact]
    public void The_check_catches_the_v1_0_60_pattern()
    {
        // The shape of the old Button.Base press trigger, and a named target with the wrong transform.
        var doc = XDocument.Parse("""
            <ResourceDictionary xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                                xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
              <Style x:Key="Old" TargetType="Button">
                <Style.Triggers>
                  <Trigger Property="IsPressed" Value="True">
                    <Trigger.EnterActions><BeginStoryboard><Storyboard>
                      <DoubleAnimation Storyboard.TargetProperty="RenderTransform.ScaleX" To="0.985"/>
                    </Storyboard></BeginStoryboard></Trigger.EnterActions>
                  </Trigger>
                </Style.Triggers>
              </Style>
              <ControlTemplate x:Key="T">
                <Grid>
                  <Border x:Name="Wrong"><Border.RenderTransform><TranslateTransform/></Border.RenderTransform></Border>
                  <Border x:Name="Right"><Border.RenderTransform><ScaleTransform/></Border.RenderTransform></Border>
                  <Border x:Name="Group"><Border.RenderTransform><TransformGroup><ScaleTransform/><TranslateTransform/></TransformGroup></Border.RenderTransform></Border>
                </Grid>
                <ControlTemplate.Triggers>
                  <Trigger Property="IsPressed" Value="True">
                    <Trigger.EnterActions><BeginStoryboard><Storyboard>
                      <DoubleAnimation Storyboard.TargetName="Wrong" Storyboard.TargetProperty="RenderTransform.ScaleX" To="1"/>
                      <DoubleAnimation Storyboard.TargetName="Right" Storyboard.TargetProperty="RenderTransform.ScaleX" To="1"/>
                      <DoubleAnimation Storyboard.TargetName="Group" Storyboard.TargetProperty="(UIElement.RenderTransform).(TransformGroup.Children)[0].(ScaleTransform.ScaleY)" To="1"/>
                      <DoubleAnimation Storyboard.TargetName="Group" Storyboard.TargetProperty="(UIElement.RenderTransform).(TransformGroup.Children)[0].(TranslateTransform.X)" To="1"/>
                    </Storyboard></BeginStoryboard></Trigger.EnterActions>
                  </Trigger>
                </ControlTemplate.Triggers>
              </ControlTemplate>
            </ResourceDictionary>
            """, LoadOptions.SetLineInfo);

        var problems = Check("sample.xaml", doc);
        Assert.Equal(3, problems.Count);
        Assert.Contains(problems, p => p.Contains("no Storyboard.TargetName"));
        Assert.Contains(problems, p => p.Contains("'Wrong'"));
        Assert.Contains(problems, p => p.Contains("'Group'") && p.Contains("TranslateTransform"));
    }

    [Fact]
    public void Every_press_scaled_button_style_uses_the_shared_helper()
    {
        // Button.Base and Button.PlayHero are the two roots every launcher button style descends from.
        var buttons = File.ReadAllText(Path.Combine(AppDir, "Theme", "Buttons.xaml"));
        Assert.Equal(2, Regex.Matches(buttons, @"Property=""ui:Press\.Down"" Value=""True""").Count);
    }
}
