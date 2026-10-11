using System.Text.RegularExpressions;

namespace OriginSetup.Engine;

// The Minecraft versions offered as "default version" on the Customize page:
// the "versions" arrays of src/mods/live-modules.json (embedded at build
// time), newest first. That list must equal the launcher's
// VersionManager.OriginBuilds keys — tools/release/check_consistency.py
// fails CI if they drift. A tiny pattern parser rather than a JSON library:
// .NET Framework has none built in and the file's shape is ours.
public static class VersionChoices
{
    public static IReadOnlyList<string> Parse(string liveModulesJson)
    {
        var versions = new List<string>();
        foreach (Match array in Regex.Matches(liveModulesJson, "\"versions\"\\s*:\\s*\\[([^\\]]*)\\]"))
            foreach (Match v in Regex.Matches(array.Groups[1].Value, "\"([^\"]+)\""))
                versions.Add(v.Groups[1].Value);
        return versions.Distinct().OrderByDescending(Key, Comparer<long[]>.Create(Compare)).ToList();
    }

    public static IReadOnlyList<string> Embedded()
    {
        using var s = typeof(VersionChoices).Assembly.GetManifestResourceStream("live-modules.json");
        if (s == null) return new string[0];
        using var r = new StreamReader(s);
        return Parse(r.ReadToEnd());
    }

    private static long[] Key(string version) =>
        version.Split('.').Select(p => long.TryParse(p, out var n) ? n : 0).ToArray();

    private static int Compare(long[] a, long[] b)
    {
        for (int i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            long x = i < a.Length ? a[i] : 0, y = i < b.Length ? b[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }
}
