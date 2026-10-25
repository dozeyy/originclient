using System.Text.RegularExpressions;
using System.Xml.Linq;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>
/// The account pill's proportions (Phase 3 U3.5, DESIGN-SYSTEM 11.5): the head is its container's height minus 8, whole
/// multiples of the face's 8 pixels, and stays whole screen pixels per face pixel at any scale. Tokens are read as plain
/// XML (this project runs without WPF); the drawn result is judged in the launcher-shots 16* set.
/// </summary>
public class AccountPillTests
{
    private static readonly XNamespace Wpf = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";
    private static readonly string AppDir = Path.Combine(TestRepo.Root, "src", "OriginLauncher.App");

    private static double Token(string key)
    {
        var doc = XDocument.Load(Path.Combine(AppDir, "Theme", "Spacing.xaml"));
        var el = doc.Descendants().Single(e => (string?)e.Attribute(X + "Key") == key);
        return double.Parse(el.Value.Trim(), System.Globalization.CultureInfo.InvariantCulture);
    }

    [Fact]
    public void PillHeadIsThePillHeightMinusEight()
    {
        Assert.Equal(Token("Size.ControlMd") - 8, Token("Size.PillHead"));
    }

    [Fact]
    public void DrawerRowHeadIsItsRowHeightMinusEight()
    {
        // Row = head + 2 x (1 px edge + 3 px padding) = 48; head 40.
        var spacing = File.ReadAllText(Path.Combine(AppDir, "Theme", "Spacing.xaml"));
        Assert.Matches(@"x:Key=""Padding\.AccountRow"">3<", spacing);
        Assert.Equal(48 - 8, Token("Size.RowHead"));
    }

    [Theory]
    [InlineData("Size.PillHead")]
    [InlineData("Size.RowHead")]
    public void HeadsAreWholeMultiplesOfTheEightPixelFace(string key)
    {
        Assert.Equal(0, Token(key) % HeadFit.Pixels);
    }

    [Theory]
    [InlineData(24, 1.0, 24)]
    [InlineData(24, 2.0, 24)]
    [InlineData(24, 1.25, 19.2)]  // 30 screen pixels -> 24 (3 x 8)
    [InlineData(24, 1.5, 21.333333333)]  // 36 -> 32 screen pixels (4 x 8)
    [InlineData(40, 1.0, 40)]
    [InlineData(40, 1.5, 37.333333333)]  // 60 -> 56 (7 x 8)
    public void CrispHeadIsAWholeNumberOfScreenPixelsPerFacePixel(double box, double scale, double side)
    {
        var (_, _, got) = HeadFit.Square(box, box, scale);
        Assert.Equal(side, got, 6);
        Assert.Equal(0, Math.Round(got * scale) % HeadFit.Pixels);
    }

    [Theory]
    [InlineData(1.0)]
    [InlineData(1.25)]
    [InlineData(1.5)]
    [InlineData(1.75)]
    [InlineData(2.0)]
    public void CrispHeadStartsOnAWholeScreenPixelAndStaysInsideItsBox(double scale)
    {
        var (left, top, side) = HeadFit.Square(24, 24, scale);
        Assert.Equal(0, Math.Round(left * scale) - left * scale, 6);
        Assert.Equal(0, Math.Round(top * scale) - top * scale, 6);
        Assert.True(left >= 0 && top >= 0 && left + side <= 24 + 1e-9 && top + side <= 24 + 1e-9);
    }

    [Fact]
    public void StoreIsTheHomeCardAndNotATopBarButton()
    {
        // The top bar lost its Store button (Workbench Home): the right-hand group is update badge, Import, Friends, ...
        var xaml = XDocument.Load(Path.Combine(AppDir, "MainWindow.xaml"));
        Assert.DoesNotContain(xaml.Descendants(Wpf + "Button"), b => (string?)b.Attribute(X + "Name") == "StoreButton");
        var group = xaml.Descendants(Wpf + "StackPanel").Single(p => (string?)p.Attribute(X + "Name") == "BarActions");
        var names = group.Elements().Select(e => (string?)e.Attribute(X + "Name")).Where(n => n != null).ToList();
        Assert.True(names.IndexOf("UpdateBadge") < names.IndexOf("ImportButton"));
        Assert.DoesNotContain("WebsiteButton", names); // the Website link lives in the brand lockup now
        // The Store card is on Home, a strip card with no hover label, opening the configured store.
        var home = XDocument.Load(Path.Combine(AppDir, "UI", "Pages", "HomePage.xaml"));
        var store = home.Descendants(Wpf + "Button").Single(b => (string?)b.Attribute(X + "Name") == "StoreCard");
        Assert.Equal("{StaticResource Button.StripCard}", (string?)store.Attribute("Style"));
        Assert.Null(store.Attribute("ToolTip")); // only Import, Friends, Minimize and Close carry hover labels
        var code = File.ReadAllText(Path.Combine(AppDir, "UI", "Pages", "HomePage.xaml.cs"));
        Assert.Matches(@"StoreCard_Click\([^)]*\)\s*=>\s*Core\.Promos\.ExternalLink\.Open\(Core\.Promos\.OriginLinks\.Store\)", code);
    }

    [Fact]
    public void ThePillNeverTouchesAnAccountOrAToken()
    {
        // The pill and its tooltip read the list for display only: no store write, vault or token call in the chip code.
        var main = File.ReadAllText(Path.Combine(AppDir, "MainWindow.xaml.cs"));
        var start = main.IndexOf("private void RefreshAccountChip()", StringComparison.Ordinal);
        var end = main.IndexOf("private bool AccountNameIsTrimmed()", StringComparison.Ordinal);
        Assert.True(start > 0 && end > start);
        var chip = main[start..end];
        Assert.DoesNotMatch(new Regex(@"AccountStore\.(Save|Update|Upsert|SetSelected|Protect)|TryUnprotect|SettingsStore\.Update"), chip);
    }
}
