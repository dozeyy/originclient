using System.Reflection;
using System.Text.RegularExpressions;
using Avalonia.Media;

namespace OriginLauncher.Desktop.Theme;

// Origin Line, the launcher's one icon set (DESIGN-SYSTEM.md section 10): the 24 px masters in the WPF
// launcher's Theme/Icons.xaml, read from that file at startup (embedded by OriginLauncher.Desktop.props),
// never retyped here. Kit.Icon draws them stroke-only at 1.7 px with round caps and joins.
public static partial class Icons
{
    private static readonly Dictionary<string, string> Data = Load();
    private static readonly Dictionary<string, Geometry> Parsed = new(StringComparer.Ordinal);

    private static Dictionary<string, string> Load()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Origin.Theme.Icons.xaml")
            ?? throw new InvalidOperationException("Icons.xaml is not embedded (OriginLauncher.Desktop.props).");
        using var reader = new StreamReader(stream);
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (Match m in Entry().Matches(reader.ReadToEnd()))
            map[m.Groups[1].Value] = m.Groups[2].Value.Trim();
        return map;
    }

    [GeneratedRegex("<Geometry x:Key=\"Icon\\.([A-Za-z.]+)\">([^<]+)</Geometry>")]
    private static partial Regex Entry();

    public static bool Has(string name) => Data.ContainsKey(name);

    /// <summary>The geometry of an icon by its Icons.xaml key without "Icon." ("Store", "Emblem.Star").</summary>
    public static Geometry Get(string name)
    {
        if (Parsed.TryGetValue(name, out var g)) return g;
        if (!Data.TryGetValue(name, out var data)) throw new KeyNotFoundException("No icon Icon." + name);
        g = Geometry.Parse(data);
        Parsed[name] = g;
        return g;
    }

    public const string Home = "Home", Mods = "Mods", Settings = "Settings", Account = "Account", Download = "Download",
        Folder = "Folder", Trash = "Trash", Back = "Back", ArrowUpRight = "ArrowUpRight", External = "External",
        Minimize = "Minimize", Close = "Close", Friends = "Friends", UserPlus = "UserPlus", Send = "Send", Chat = "Chat",
        Crown = "Crown", ChevronDown = "ChevronDown", Import = "Import", ImportIn = "ImportIn", Plus = "Plus", Check = "Check",
        Minus = "Minus", UserMinus = "UserMinus", More = "More", Alert = "Alert", Block = "Block", DownloadArrow = "DownloadArrow",
        Profiles = "Profiles", Play = "Play", Store = "Store", Edit = "Edit", Server = "Server", Jar = "Jar", Drop = "Drop",
        Copy = "Copy", Sync = "Sync", Globe = "Globe";
}
