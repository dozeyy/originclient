using System.Text.RegularExpressions;
using System.Xml.Linq;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Icon-only buttons size from their glyph, never to a fixed box. The Friends drawer's icon buttons were
/// a fixed 28 px with 7 px padding (a 14 px slot) around 18 px glyphs, so WPF cut every glyph at the
/// slot's edge: "..." showed two dots, the X and the add-friend icon lost their right side (2026-10-18).
/// With MinWidth/MinHeight a bigger glyph makes the button bigger instead, so that whole class of bug
/// can't come back. Reads the launcher's XAML as plain XML (this test project runs without WPF); the
/// render-time check that measures what is actually drawn is RenderShots.ClipCheck in launcher-shots.
/// </summary>
public class IconButtonTests
{
    private static readonly XNamespace Wpf = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";
    private static readonly string[] ButtonTags = ["Button", "ToggleButton", "RepeatButton"];

    private static List<(string File, XDocument Doc)> Xaml()
    {
        var app = Path.Combine(TestRepo.Root, "src", "OriginLauncher.App");
        return Directory.EnumerateFiles(app, "*.xaml", SearchOption.AllDirectories)
            .Where(f => !f.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")
                        && !f.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}"))
            .Select(f => (Path.GetRelativePath(app, f), XDocument.Load(f, LoadOptions.SetLineInfo)))
            .ToList();
    }

    // A button whose only content is a Path (an icon).
    private static IEnumerable<(string File, XElement Button)> IconButtons(List<(string File, XDocument Doc)> files) =>
        from f in files
        from b in f.Doc.Descendants()
        where ButtonTags.Contains(b.Name.LocalName) && b.Name.Namespace == Wpf
        let kids = b.Elements().Where(e => !e.Name.LocalName.Contains('.')).ToList()
        where kids.Count == 1 && kids[0].Name == Wpf + "Path"
        select (f.File, b);

    private static string? StaticKey(string? value) =>
        value == null ? null : Regex.Match(value, @"\{StaticResource\s+([^}\s]+)\s*\}") is { Success: true } m ? m.Groups[1].Value : null;

    private static string Where(string file, XElement e) => $"{file}:{((System.Xml.IXmlLineInfo)e).LineNumber}";

    [Fact]
    public void TheLauncherHasIconButtons()
    {
        // Guards the test itself: if the XAML moved or the shape changed, the rules below would pass on nothing.
        Assert.True(IconButtons(Xaml()).Count() >= 10);
    }

    [Fact]
    public void IconButtonsHaveNoFixedSize()
    {
        var bad = IconButtons(Xaml())
            .Where(x => x.Button.Attribute("Width") != null || x.Button.Attribute("Height") != null)
            .Select(x => Where(x.File, x.Button))
            .ToList();
        Assert.True(bad.Count == 0, "Icon buttons with a fixed Width/Height (use MinWidth/MinHeight): " + string.Join(", ", bad));
    }

    [Fact]
    public void IconButtonStylesHaveNoFixedSize()
    {
        var files = Xaml();
        var styles = (from f in files
                      from s in f.Doc.Descendants(Wpf + "Style")
                      let key = (string?)s.Attribute(X + "Key")
                      where key != null
                      select (f.File, Key: key, Style: s))
            .GroupBy(s => s.Key).ToDictionary(g => g.Key, g => g.First());

        var bad = new List<string>();
        foreach (var key in IconButtons(files).Select(x => StaticKey((string?)x.Button.Attribute("Style"))).OfType<string>().Distinct())
        {
            // The style and everything it is BasedOn.
            for (var k = key; k != null && styles.TryGetValue(k, out var s); k = StaticKey((string?)s.Style.Attribute("BasedOn")))
            {
                foreach (var setter in s.Style.Elements(Wpf + "Setter"))
                {
                    var property = (string?)setter.Attribute("Property");
                    if (property is "Width" or "Height")
                        bad.Add($"{k} sets {property} ({Where(s.File, setter)}), used by an icon button through {key}");
                }
            }
        }
        Assert.True(bad.Count == 0, "Icon button styles with a fixed size (use MinWidth/MinHeight): " + string.Join("; ", bad));
    }
}
