using System.Text.RegularExpressions;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// Colour tweens in code go through Motion.PublishColor only (v1.0.60 crash: "Cannot animate the 'Color'
/// property ... because the object is sealed or frozen", stepping version families with the arrow keys).
/// Application and theme resource dictionaries freeze every Freezable written into them, so a brush that is
/// published first and animated second throws on every change. PublishColor starts the animation before the
/// assignment, which keeps the brush live. This project runs without WPF, so the rule is read from the
/// source: outside Motion.cs, no C# file may BeginAnimation a Color property itself. (XAML storyboards are
/// fine: a Storyboard clones a frozen brush in its target path before animating it.)
/// </summary>
public class BrushTweenTests
{
    private static readonly string AppDir = Path.Combine(TestRepo.Root, "src", "OriginLauncher.App");

    // BeginAnimation(SolidColorBrush.ColorProperty, ...), BeginAnimation(ColorProperty, ...), and the
    // GradientStop / Color-typed variants: anything whose property argument ends in "ColorProperty".
    private static readonly Regex ColorTween = new(@"BeginAnimation\s*\(\s*[\w.]*ColorProperty\b");

    [Fact]
    public void Colour_tweens_only_go_through_Motion_PublishColor()
    {
        var offenders = Directory.EnumerateFiles(AppDir, "*.cs", SearchOption.AllDirectories)
            .Where(f => !f.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}")
                     && !f.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}"))
            .Where(f => Path.GetFileName(f) != "Motion.cs")
            .SelectMany(f => File.ReadLines(f).Select((line, i) => (f, i, line)))
            .Where(x => ColorTween.IsMatch(x.line))
            .Select(x => $"{Path.GetRelativePath(AppDir, x.f)}:{x.i + 1}: {x.line.Trim()}")
            .ToList();
        Assert.True(offenders.Count == 0,
            "Animate brush colours with Motion.PublishColor (the app dictionary freezes brushes written into it):\n"
            + string.Join("\n", offenders));
    }

    [Fact]
    public void PublishColor_starts_the_tween_before_publishing()
    {
        // The order is the whole fix: BeginAnimation must come before the resource assignment.
        var src = File.ReadAllText(Path.Combine(AppDir, "UI", "Motion.cs"));
        var body = src[src.IndexOf("PublishColor(", StringComparison.Ordinal)..];
        var tween = body.IndexOf("BeginAnimation(", StringComparison.Ordinal);
        var publish = body.IndexOf("res[key] = brush", StringComparison.Ordinal);
        Assert.True(tween >= 0 && publish > tween, "Motion.PublishColor must BeginAnimation before res[key] = brush.");
    }
}
