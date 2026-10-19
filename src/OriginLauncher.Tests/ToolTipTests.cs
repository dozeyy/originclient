using System.Text.RegularExpressions;
using System.Xml.Linq;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Hover labels (Will, 2026-10-19: "whenever i hover over an icon that doesnt have a clear explanation it should
/// have a small text that just says what it does"). Three rules, read straight from the launcher's XAML (this
/// project runs without WPF; the look itself is proven by the tooltip shots in launcher-shots):
///   1. One global ToolTip style, merged into App.xaml, built from tokens only (no literal colors).
///   2. Every icon-only button has a ToolTip AND an AutomationProperties.Name.
///   3. That label is plain and short (1-3 words; a key hint in brackets, "(Esc)", doesn't count).
/// </summary>
public class ToolTipTests
{
    private static readonly XNamespace Wpf = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
    private static readonly string[] ButtonTags = ["Button", "ToggleButton", "RepeatButton"];
    private static readonly string AppDir = Path.Combine(TestRepo.Root, "src", "OriginLauncher.App");

    private static readonly string[] GlyphShapes = ["Path", "Image", "Viewbox", "Canvas", "Polygon", "Polyline", "Line"];

    /// <summary>
    /// An icon-only button: no words anywhere in it (no Content text, no TextBlock/Run with letters, no binding
    /// that could fill one), but a drawn glyph: a Path/Image/shape, or a TextBlock holding only a symbol.
    /// Walks the whole subtree, so a Path nested in a Grid or Viewbox still counts.
    /// </summary>
    private static bool IsIconOnly(XElement b)
    {
        static bool HasLetters(string? t) => t is not null && (t.TrimStart().StartsWith('{') || Regex.IsMatch(t, @"\p{L}{2,}"));
        if (HasLetters((string?)b.Attribute("Content"))) return false;
        var inner = b.Descendants().Where(e => e.Name.Namespace == Wpf && !e.Name.LocalName.Contains('.')).ToList();
        var words = inner.Where(e => e.Name.LocalName is "TextBlock" or "Run" or "Label" or "AccessText")
                         .Select(e => (string?)e.Attribute("Text") ?? (string?)e.Attribute("Content") ?? e.Value);
        if (words.Any(HasLetters)) return false;
        // A TextBlock with no Text attribute is filled from code (a banner card's title): words, not an icon.
        if (inner.Any(e => e.Name.LocalName == "TextBlock" && e.Attribute("Text") is null && string.IsNullOrWhiteSpace(e.Value))) return false;
        return inner.Any(e => GlyphShapes.Contains(e.Name.LocalName) || e.Name.LocalName == "TextBlock");
    }

    private static IEnumerable<string> UiXamlFiles() =>
        Directory.EnumerateFiles(AppDir, "*.xaml", SearchOption.AllDirectories)
            .Where(f => !f.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}")
                        && !f.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}")
                        && !f.Contains($"{Path.DirectorySeparatorChar}Theme{Path.DirectorySeparatorChar}")); // Theme/ holds control templates, not placed buttons

    private static IEnumerable<(string File, XElement Button)> AllButtons() =>
        from f in UiXamlFiles()
        let name = Path.GetRelativePath(AppDir, f)
        from b in XDocument.Load(f, LoadOptions.SetLineInfo).Descendants()
        where ButtonTags.Contains(b.Name.LocalName) && b.Name.Namespace == Wpf
        select (name, b);

    private static IEnumerable<(string File, XElement Button)> IconButtons() => AllButtons().Where(x => IsIconOnly(x.Button));

    private static string Where(string file, XElement e) => $"{file}:{((System.Xml.IXmlLineInfo)e).LineNumber}";

    [Fact]
    public void OneGlobalToolTipStyleBuiltFromTokens()
    {
        var path = Path.Combine(AppDir, "Theme", "ToolTips.xaml");
        var doc = XDocument.Load(path);
        Assert.Contains(doc.Descendants(Wpf + "Style"),
            s => (string?)s.Attribute("TargetType") == "ToolTip" && s.Attribute(XNamespace.Get("http://schemas.microsoft.com/winfx/2006/xaml") + "Key") == null);
        Assert.DoesNotMatch(new Regex(@"#[0-9A-Fa-f]{3,8}\b"), File.ReadAllText(path)); // colors come from Colors.xaml
        Assert.Contains("Theme/ToolTips.xaml", File.ReadAllText(Path.Combine(AppDir, "App.xaml")));
    }

    [Fact]
    public void TheLauncherHasIconButtonsToLabel()
    {
        Assert.True(IconButtons().Count() >= 15); // guards the test itself: if the XAML moved, the rules below would pass on nothing
    }

    [Fact]
    public void EveryIconButtonHasAToolTipAndAnAutomationName()
    {
        var bad = IconButtons()
            .Where(x => string.IsNullOrWhiteSpace((string?)x.Button.Attribute("ToolTip"))
                        || string.IsNullOrWhiteSpace((string?)x.Button.Attribute("AutomationProperties.Name")))
            .Select(x => Where(x.File, x.Button))
            .ToList();
        Assert.True(bad.Count == 0, "Icon-only buttons without a ToolTip and AutomationProperties.Name: " + string.Join(", ", bad));
    }

    [Fact]
    public void EverySwitchHasAnAutomationName()
    {
        // A switch sits beside its own visible label, so it needs no hover text, but a screen reader needs the name.
        var bad = AllButtons()
            .Where(x => ((string?)x.Button.Attribute("Style") ?? "").Contains("Toggle.Switch")
                        && string.IsNullOrWhiteSpace((string?)x.Button.Attribute("AutomationProperties.Name")))
            .Select(x => Where(x.File, x.Button)).ToList();
        Assert.True(bad.Count == 0, "Toggle.Switch controls without AutomationProperties.Name: " + string.Join(", ", bad));
    }

    [Fact]
    public void IconButtonLabelsAreShort()
    {
        // The Import drawer is being rebuilt on its own branch; its labels are checked above for presence only.
        var bad = IconButtons()
            .Where(x => !x.File.StartsWith("UI" + Path.DirectorySeparatorChar + "Controls" + Path.DirectorySeparatorChar + "Import", StringComparison.Ordinal))
            .Select(x => (x.File, x.Button, Tip: (string?)x.Button.Attribute("ToolTip") ?? ""))
            .Where(x => !x.Tip.StartsWith('{') && Regex.Replace(x.Tip, @"\s*\([^)]*\)", "").Split(' ', StringSplitOptions.RemoveEmptyEntries).Length > 3)
            .Select(x => $"{Where(x.File, x.Button)} \"{x.Tip}\"")
            .ToList();
        Assert.True(bad.Count == 0, "Icon-only button labels over 3 words: " + string.Join(", ", bad));
    }
}
