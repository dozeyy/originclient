using System.Globalization;
using System.Reflection;
using System.Text.RegularExpressions;
using Avalonia;
using Avalonia.Media;

namespace OriginLauncher.Desktop.Theme;

// The Workbench design system (docs/overhaul/DESIGN-SYSTEM.md) for the Avalonia launcher.
//
// Colours are not typed here: they are read from the WPF launcher's own token file
// (src/OriginLauncher.App/Theme/Colors.xaml, embedded by OriginLauncher.Desktop.props), so a colour
// changed there changes on every OS. Sizes, type, radii and motion are the design system's numbers
// (sections 3-9) and live only in this file; views never type a colour or a font size.
public static partial class Palette
{
    private static readonly Dictionary<string, Color> Colors = Load();

    private static Dictionary<string, Color> Load()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Origin.Theme.Colors.xaml")
            ?? throw new InvalidOperationException("Colors.xaml is not embedded (OriginLauncher.Desktop.props).");
        using var reader = new StreamReader(stream);
        var xaml = reader.ReadToEnd();
        var map = new Dictionary<string, Color>(StringComparer.Ordinal);
        foreach (Match m in ColorEntry().Matches(xaml))
            map[m.Groups[1].Value] = Color.Parse(m.Groups[2].Value);
        return map;
    }

    [GeneratedRegex("<Color x:Key=\"Color\\.([A-Za-z.]+)\">\\s*(#[0-9A-Fa-f]{6,8})\\s*</Color>")]
    private static partial Regex ColorEntry();

    /// <summary>A colour by its Colors.xaml key without the "Color." prefix ("Bg", "Hue.Gold").</summary>
    public static Color Get(string key) =>
        Colors.TryGetValue(key, out var c) ? c : throw new KeyNotFoundException("No colour token Color." + key);

    private static readonly Dictionary<string, ISolidColorBrush> Brushes = new(StringComparer.Ordinal);

    /// <summary>A frozen brush for a colour token (one instance per key).</summary>
    public static ISolidColorBrush Brush(string key)
    {
        if (Brushes.TryGetValue(key, out var b)) return b;
        b = new Avalonia.Media.Immutable.ImmutableSolidColorBrush(Get(key));
        Brushes[key] = b;
        return b;
    }

    public static ISolidColorBrush Bg => Brush("Bg");
    public static ISolidColorBrush BgAlt => Brush("BgAlt");
    public static ISolidColorBrush Panel => Brush("Panel");
    public static ISolidColorBrush Nav => Brush("PanelGlass");
    public static ISolidColorBrush Card => Brush("Card");
    public static ISolidColorBrush CardHover => Brush("CardAlt");
    public static ISolidColorBrush SurfacePress => Brush("SurfacePress");
    public static ISolidColorBrush Field => Brush("Field");
    public static ISolidColorBrush Line => Brush("SoftStroke");
    public static ISolidColorBrush LineStrong => Brush("Stroke");
    public static ISolidColorBrush LineHover => Brush("StrokeHover");
    public static ISolidColorBrush Text => Brush("Text");
    public static ISolidColorBrush TextDim => Brush("TextDim");
    public static ISolidColorBrush TextMuted => Brush("TextMuted");
    public static ISolidColorBrush Accent => Brush("Accent");
    public static ISolidColorBrush AccentHover => Brush("AccentHover");
    public static ISolidColorBrush AccentPress => Brush("AccentPress");
    public static ISolidColorBrush AccentSoft => Brush("AccentSoft");
    public static ISolidColorBrush AccentLine => Brush("AccentBorder");
    public static ISolidColorBrush OnAccent => Brush("TextOnAccent");
    public static ISolidColorBrush Success => Brush("Success");
    public static ISolidColorBrush Warning => Brush("Warning");
    public static ISolidColorBrush Info => Brush("Info");
    public static ISolidColorBrush Danger => Brush("Danger");
    public static ISolidColorBrush DangerText => Brush("DangerText");
    public static ISolidColorBrush DangerSoft => Brush("DangerSoft");
    public static ISolidColorBrush DangerSoftHover => Brush("DangerSoftHover");
    public static ISolidColorBrush GhostHover => Brush("GhostHover");
    public static ISolidColorBrush GhostPress => Brush("GhostPress");
    public static ISolidColorBrush GhostRest => Brush("GhostRest");
    public static ISolidColorBrush PillRest => Brush("PillRest");
    public static ISolidColorBrush ProgressTrack => Brush("ProgressTrack");
    public static ISolidColorBrush Scrim => Brush("Scrim");
    public static ISolidColorBrush Sheet => Brush("Sheet");
    public static ISolidColorBrush PageDim => Brush("PageDim");
    public static ISolidColorBrush LaunchScrim => Brush("LaunchScrim");
    public static ISolidColorBrush NumeralInk => Brush("NumeralInk");
    public static ISolidColorBrush NumeralShadow => Brush("NumeralShadow");
    public static ISolidColorBrush OnArt => Brush("OnArt");
    public static ISolidColorBrush SwitchKnobOff => Brush("SwitchKnobOff");
    public static ISolidColorBrush HueEmerald => Brush("Hue.Emerald");
    public static ISolidColorBrush HueSky => Brush("Hue.Sky");
    public static ISolidColorBrush HueGold => Brush("Hue.Gold");
    public static ISolidColorBrush HueLilac => Brush("Hue.Lilac");
    public static ISolidColorBrush HueCoral => Brush("Hue.Coral");
    public static ISolidColorBrush TileEmerald => Brush("HueTile.Emerald");
    public static ISolidColorBrush TileSky => Brush("HueTile.Sky");
    public static ISolidColorBrush TileGold => Brush("HueTile.Gold");
    public static ISolidColorBrush TileLilac => Brush("HueTile.Lilac");
    public static ISolidColorBrush TileCoral => Brush("HueTile.Coral");
    public static ISolidColorBrush ChipGold => Brush("HueChip.Gold");
    public static ISolidColorBrush ChipGoldHover => Brush("HueChip.GoldHover");

    /// <summary>Fill.ArtShade (section 12): clear to 40 %, bg 38 % at 66 %, bg 92 % at the bottom.</summary>
    public static IBrush ArtShade { get; } = new LinearGradientBrush
    {
        StartPoint = new RelativePoint(0, 0, RelativeUnit.Relative),
        EndPoint = new RelativePoint(0, 1, RelativeUnit.Relative),
        GradientStops =
        {
            new GradientStop(Get("ScrimClear"), 0.4),
            new GradientStop(Get("ScrimArt"), 0.66),
            new GradientStop(Get("ScrimStrong"), 1),
        },
    }.ToImmutable();

    /// <summary>The card corner shade (11.6): bg from 88 % at the bottom-left corner to 0 at the edge.</summary>
    public static IBrush CornerShade { get; } = new RadialGradientBrush
    {
        Center = new RelativePoint(0, 1, RelativeUnit.Relative),
        GradientOrigin = new RelativePoint(0, 1, RelativeUnit.Relative),
        RadiusX = new RelativeScalar(1.0, RelativeUnit.Relative),
        RadiusY = new RelativeScalar(0.8, RelativeUnit.Relative),
        GradientStops =
        {
            new GradientStop(Get("ScrimStrong"), 0),
            new GradientStop(Get("ScrimSoft"), 0.45),
            new GradientStop(Get("ScrimClear"), 1),
        },
    }.ToImmutable();

    /// <summary>shadow.overlay = 0 12 32 rgba(0,0,0,.40): menus, the version sheet, dialogs, toasts. The only shadow.</summary>
    public static BoxShadows OverlayShadow { get; } = new(new BoxShadow
    {
        OffsetX = 0, OffsetY = 12, Blur = 32, Color = Color.FromArgb(0x66, 0, 0, 0),
    });

    /// <summary>Keyboard focus ring (section 8): 2 px gap in bg, then 2 px accentLine.</summary>
    public static BoxShadows FocusRing { get; } = new(
        new BoxShadow { Spread = 2, Color = Get("Bg") },
        [new BoxShadow { Spread = 4, Color = Get("AccentBorder") }]);
}

/// <summary>Type scale (section 3). Font sizes live only here.</summary>
public static class TypeScale
{
    public const double Label = 11;        // UPPER, 600, +0.10 em
    public const double LabelLg = 16;
    public const double Caption = 12;      // 500
    public const double Body = 13.5;       // 400
    public const double Title = 16;        // 600
    public const double Action = 20;       // 700, Play only
    public const double Heading = 22;      // 700
    public const double Display = 32;      // 800
    public const double ControlSm = 12;    // labels of controls under 32 tall
    public const double Badge = 10.5;      // count badge on icons
    public const double Mono = 12.5;       // crash details, paths

    public const double LabelTracking = 0.10;   // em
    public const double NavTracking = 0.14;     // em (nav tabs, MY MODS)
    public const double NumeralTracking = -0.04;
    public const double CoverTracking = 0.16;   // em (the launch cover's eyebrow and family label)

    /// <summary>Letter spacing in px for a tracking in em at a size.</summary>
    public static double Track(double em, double size) => em * size;
}

/// <summary>Spacing (section 4): 4 px base, 8 px rhythm.</summary>
public static class Space
{
    public const double S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 20, S6 = 24, S8 = 32, S10 = 40, S12 = 48;
    public const double Gutter = 24;
}

/// <summary>Corner radii (section 6). Capsules use height / 2 (Kit.Capsule), never a big constant.</summary>
public static class Radius
{
    public const double Xs = 4, Sm = 6, Md = 8, Lg = 12, Dialog = 16;
    /// <summary>A selection ring drawn 4 px outside an lg card (Radius.Ring.Lg).</summary>
    public const double RingLg = 14;
}

/// <summary>Control and layout sizes (sections 4, 5, 11, 13).</summary>
public static class Sizes
{
    public const double ControlSm = 28, ControlMd = 32, ControlLg = 36, ControlXl = 40;
    public const double Bar = 48;
    public const double IconSm = 14, Icon = 16, IconLg = 18, IconStroke = 1.7;
    public const double SwitchW = 32, SwitchH = 18, SwitchKnob = 14;
    public const double HomeSwitchW = 36, HomeSwitchH = 20, HomeSwitchKnob = 14;
    public const double Checkbox = 16, Progress = 3, Dot = 6, Badge = 20;
    public const double PillHead = 24, Drawer = 380, Dialog = 440;
    public const double LogoBar = 24, WordmarkBar = 16;
    public const double Strip = 64, PlayWidth = 208, StripThumb = 48;
    public const double WindowW = 1180, WindowH = 740, WindowMinW = 960, WindowMinH = 600;
    public const double AccountNameMax = 160, ProfileNameMax = 180;
    public const double UpdateChipMinWindow = 1100, WordmarkMinWindow = 1000;
    public const double PageMaxWidth = 1000;
    // The version sheet (Size.Sheet.*) and its step-2 family panel (Size.FamilyPanel.*): a share of the width, clamped.
    public const double SheetMin = 912, SheetMax = 1600, SheetShare = 0.8;
    public const double FamilyPanelMin = 320, FamilyPanelMax = 520, FamilyPanelShare = 0.44;
    // The launch cover: the logo, the progress track and the stage column (Size.Logo.Hero, LaunchLoadingOverlay).
    public const double LogoHero = 48, CoverTrack = 420, CoverStage = 520;
    // Crash windows (CrashReportWindow 720 wide, CrashWindow 660 x 560) and their details well.
    public const double CrashReportW = 720, CrashW = 660, CrashH = 560, CrashDetailsH = 200;
    /// <summary>A large hue tile (Size.TileLg): the crash windows' alert tile.</summary>
    public const double TileLg = 40;
    /// <summary>The Fix Compatibility prompt's plan list scrolls past this height.</summary>
    public const double FixListMaxH = 232;
    /// <summary>Home's status line: at most this wide, 16 px lines (Size.StatusWidth / Size.StatusLine).</summary>
    public const double StatusWidth = 400, StatusLine = 16;
}

/// <summary>Motion (section 9). Reduced motion (and render runs) set Off: every duration is 0.</summary>
public static class Motion
{
    public static bool Off { get; set; }
    public static TimeSpan Ms(double ms) => Off ? TimeSpan.Zero : TimeSpan.FromMilliseconds(ms);
    public static TimeSpan Press => Ms(80);
    public static TimeSpan Hover => Ms(120);
    public static TimeSpan Base => Ms(160);
    public static TimeSpan Enter => Ms(220);
    public static TimeSpan Exit => Ms(140);
    public static TimeSpan Scene => Ms(260);
}

/// <summary>Number formatting that never follows the machine's culture (ASCII UI).</summary>
public static class Fmt
{
    public static string N(double v, string f = "0") => v.ToString(f, CultureInfo.InvariantCulture);
}
