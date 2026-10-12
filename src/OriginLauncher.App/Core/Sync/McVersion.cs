namespace OriginLauncher.App.Core.Sync;

// Minimal Minecraft version ordering for the era cliffs Settings Sync cares
// about ("1.16.5" < "1.19" < "1.21.11" < "26.2"). Non-numeric parts compare as 0.
public static class McVersion
{
    public static int Compare(string a, string b)
    {
        var pa = Parts(a);
        var pb = Parts(b);
        for (var i = 0; i < Math.Max(pa.Length, pb.Length); i++)
        {
            var x = i < pa.Length ? pa[i] : 0;
            var y = i < pb.Length ? pb[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }

    public static bool AtLeast(string version, string floor) => Compare(version, floor) >= 0;

    private static int[] Parts(string version) =>
        version.Split('.', '-', ' ')
            .Select(p => int.TryParse(p, out var n) ? n : 0)
            .ToArray();
}
