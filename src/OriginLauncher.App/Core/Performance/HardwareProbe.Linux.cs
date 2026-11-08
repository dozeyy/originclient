using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Performance;

// Linux: the GPUs come from sysfs (/sys/class/drm/cardN/device: PCI vendor + device id, boot_vga,
// amdgpu's mem_info_vram_total) and NVIDIA's driver's own /proc/driver/nvidia/gpus/<pci>/information
// for its model name. A few small file reads, no lspci/glxinfo process. RAM and threads are shared.
// Which GPU counts: the one driving the display (boot_vga = 1). On Linux a game runs there unless
// that launch is started with an offload variable (GpuPreference.Linux.cs), so a hybrid laptop's
// discrete GPU would promise more than the game gets. Anything unrecognised stays unknown, which
// never promises High.
public static partial class HardwareProbe
{
    /// <summary>One DRM card as sysfs describes it (pure input for <see cref="PickGpu"/>).</summary>
    internal sealed record DrmCard(int Vendor, int Device, bool BootVga, long? VramBytes, string? NvidiaModel);

    private const int Nvidia = 0x10de, Amd = 0x1002, Intel = 0x8086;
    private static readonly Regex CardName = new(@"^card\d+$", RegexOptions.CultureInvariant);

    private static (string? Name, long? VramMb, bool? Integrated) ReadGpu()
    {
        try
        {
            return PickGpu(ReadCards("/sys/class/drm"));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return (null, null, null);
        }
    }

    internal static List<DrmCard> ReadCards(string drmRoot)
    {
        var cards = new List<DrmCard>();
        if (!Directory.Exists(drmRoot)) return cards;
        foreach (var card in Directory.EnumerateDirectories(drmRoot))
        {
            if (!CardName.IsMatch(Path.GetFileName(card))) continue; // card0-HDMI-A-1 etc. are connectors
            var device = Path.Combine(card, "device");
            if (HexFile(Path.Combine(device, "vendor")) is not { } vendor) continue; // not a PCI GPU (an ARM SoC's display block)
            var id = HexFile(Path.Combine(device, "device")) ?? 0;
            var bootVga = ReadTrim(Path.Combine(device, "boot_vga")) == "1";
            long? vram = long.TryParse(ReadTrim(Path.Combine(device, "mem_info_vram_total")), out var v) && v > 0 ? v : null;
            string? model = null;
            if (vendor == Nvidia)
            {
                var pci = Path.GetFileName(Directory.ResolveLinkTarget(device, returnFinalTarget: true)?.FullName ?? "");
                model = pci.Length > 0 ? ModelLine(Path.Combine("/proc/driver/nvidia/gpus", pci, "information")) : null;
            }
            cards.Add(new DrmCard(vendor, id, bootVga, vram, model));
        }
        return cards;
    }

    /// <summary>The display card (boot_vga) when one is flagged, else the best by the Windows rule (discrete, then VRAM).</summary>
    internal static (string? Name, long? VramMb, bool? Integrated) PickGpu(IReadOnlyList<DrmCard> cards)
    {
        var described = cards.Select(c => (Card: c, Info: Describe(c, cards))).ToList();
        if (described.Count == 0) return (null, null, null);
        var display = described.Where(d => d.Card.BootVga).ToList();
        var pool = display.Count > 0 ? display : described;
        return pool
            .OrderByDescending(d => d.Info.Integrated == false)
            .ThenByDescending(d => d.Info.VramMb ?? 0)
            .First().Info;
    }

    /// <summary>A card known to be a discrete GPU (GpuPreference.Linux offloads the game to it on a hybrid laptop).</summary>
    internal static bool IsDiscrete(DrmCard card, IReadOnlyList<DrmCard> all) => Describe(card, all).Integrated == false;

    // An AMD APU whose BIOS gives it 2 GB or more of system RAM (common on Ryzen laptops and handhelds, 4-8 GB) looks
    // like a discrete Radeon by its VRAM alone, so a hybrid laptop's real discrete GPU was never used (LINUX-10). The
    // layout tells them apart: on a hybrid machine the display card (boot_vga) is the integrated one and the discrete
    // GPU sits beside it, not driving the display. So an AMD display card with a discrete card beside it is the APU,
    // whatever its VRAM. A desktop whose display card is the discrete Radeon has no second discrete card beside it.
    private static (string? Name, long? VramMb, bool? Integrated) Describe(DrmCard card, IReadOnlyList<DrmCard> all)
    {
        var info = Describe(card);
        if (card.Vendor == Amd && info.Integrated == false && card.BootVga
            && all.Any(o => !o.BootVga && Describe(o).Integrated == false))
            return ("AMD Radeon Graphics", info.VramMb, true);
        return info;
    }

    internal const int NvidiaVendor = Nvidia;

    private static (string? Name, long? VramMb, bool? Integrated) Describe(DrmCard card)
    {
        long? vramMb = card.VramBytes / (1024 * 1024);
        switch (card.Vendor)
        {
            case Nvidia:
                return (card.NvidiaModel ?? "NVIDIA GPU", vramMb, false);
            case Amd:
                // amdgpu reports an APU's carve-out of system RAM (512 MB - 2 GB by default) as its VRAM;
                // discrete Radeons start at 4 GB in anything that runs Minecraft well.
                bool? apu = vramMb is { } mb ? mb < 2048 : null;
                return (apu == true ? "AMD Radeon Graphics" : "AMD Radeon GPU", vramMb, apu);
            case Intel:
                // Arc discrete: DG2 (A-series, 0x56xx) and Battlemage (B-series, 0xe2xx); every other Intel GPU is in the CPU.
                var arc = (card.Device >> 8) is 0x56 or 0xe2;
                return (arc ? "Intel Arc GPU" : "Intel Graphics", vramMb, !arc);
            default:
                return (null, vramMb, null);
        }
    }

    private static int? HexFile(string file)
    {
        var text = ReadTrim(file);
        if (text == null) return null;
        if (text.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) text = text[2..];
        return int.TryParse(text, System.Globalization.NumberStyles.HexNumber, null, out var n) ? n : null;
    }

    private static string? ReadTrim(string file)
    {
        try { return File.Exists(file) ? File.ReadAllText(file).Trim() : null; }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return null; }
    }

    // NVIDIA's information file: "Model: \t\t NVIDIA GeForce RTX 3060".
    private static string? ModelLine(string file) => SystemInfo.ProcField(file, "Model") is { Length: > 0 } m ? m : null;
}
