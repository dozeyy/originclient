namespace OriginSetup;

// Command line. Players never type these — they exist for the Add/Remove
// Programs entry (--uninstall), its quiet variant, and CI's smoke test:
//   OriginLauncher-Setup.exe [--silent] [--dir <folder>] [--start-with-windows]
//   uninstall.exe --uninstall [--silent] [--remove-data]
//   --from-temp / --dir are added internally when the uninstaller re-launches
//   itself from %TEMP% (it cannot delete the folder it is running from).
public sealed class SetupArgs
{
    public bool Uninstall { get; private set; }
    public bool Silent { get; private set; }
    public bool RemoveData { get; private set; }
    public bool FromTemp { get; private set; }
    public bool StartWithWindows { get; private set; }
    public string? Dir { get; private set; }
    /// <summary>CI-only: render every screen to PNGs in this folder (UI/RenderTour.cs).</summary>
    public string? RenderTour { get; private set; }

    public static SetupArgs Parse(string[] args)
    {
        var a = new SetupArgs();
        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--uninstall": a.Uninstall = true; break;
                case "--silent": a.Silent = true; break;
                case "--remove-data": a.RemoveData = true; break;
                case "--from-temp": a.FromTemp = true; break;
                case "--start-with-windows": a.StartWithWindows = true; break;
                case "--dir" when i + 1 < args.Length: a.Dir = args[++i]; break;
                case "--render-tour" when i + 1 < args.Length: a.RenderTour = args[++i]; break;
            }
        }
        return a;
    }
}
