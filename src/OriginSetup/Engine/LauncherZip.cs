using System.IO.Compression;
using System.Threading;

namespace OriginSetup.Engine;

// The launcher package: OriginLauncher-win-x64.zip, the release asset CI
// makes from the launcher's publish folder (the self-update zip). Setup
// downloads it, then unpacks it into the staging folder with this.
//
// Damage never reaches the real install:
//  - Open() rejects a zip that isn't one (truncated, wrong file), has a path
//    that would escape the install folder, or lacks the launcher exe.
//  - ExtractTo() checks every file's size and CRC-32 against the zip's own
//    records. .NET Framework's ZipArchive checks neither, and deflate carries
//    no checksum, so this is what catches corrupted bytes.
public sealed class LauncherZip
{
    public sealed class Entry
    {
        internal Entry(string relativePath, long size, uint? crc, int index)
        {
            RelativePath = relativePath;
            Size = size;
            Crc = crc;
            Index = index;
        }

        /// <summary>'/'-separated path inside the install folder.</summary>
        public string RelativePath { get; }
        public long Size { get; }
        /// <summary>From the zip's central directory; null when it couldn't be read (zip64).</summary>
        internal uint? Crc { get; }
        internal int Index { get; }
    }

    private readonly string _path;

    public IReadOnlyList<Entry> Files { get; }
    public long TotalSize { get; }

    private LauncherZip(string path, List<Entry> files)
    {
        _path = path;
        Files = files;
        TotalSize = files.Sum(f => f.Size);
    }

    /// <summary>Reads and checks the zip's index. Throws <see cref="InvalidDataException"/> when it is damaged or not Origin.</summary>
    public static LauncherZip Open(string zipPath)
    {
        List<CentralRecord>? records;
        using (var fs = new FileStream(zipPath, FileMode.Open, FileAccess.Read, FileShare.Read))
            records = ReadCentralDirectory(fs);
        if (records == null)
            SetupLog.Write("zip: zip64 archive — per-file CRC check skipped, sizes still checked");

        var files = new List<Entry>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            using var zip = OpenArchive(zipPath);
            var entries = zip.Entries;
            if (records != null && records.Count != entries.Count)
                throw new InvalidDataException("Launcher package is damaged (index disagrees).");
            for (int i = 0; i < entries.Count; i++)
            {
                var e = entries[i];
                // Windows PowerShell's Compress-Archive writes '\' separators.
                var name = e.FullName.Replace('\\', '/');
                if (name.EndsWith("/", StringComparison.Ordinal))
                {
                    if (e.Length != 0) throw new InvalidDataException("Launcher package is damaged (folder with data).");
                    continue; // folder entry
                }
                var rel = CheckRelativePath(name);
                if (!seen.Add(rel)) throw new InvalidDataException($"Launcher package lists '{rel}' twice.");
                uint? crc = null;
                if (records != null)
                {
                    var r = records[i];
                    if (r.Size != uint.MaxValue && r.Size != e.Length)
                        throw new InvalidDataException("Launcher package is damaged (index disagrees).");
                    crc = r.Crc;
                }
                files.Add(new Entry(rel, e.Length, crc, i));
            }
        }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is EndOfStreamException)
        {
            throw new InvalidDataException("Launcher package is damaged: " + ex.Message, ex);
        }

        if (!files.Any(f => string.Equals(f.RelativePath, InstallLocation.LauncherExe, StringComparison.OrdinalIgnoreCase)))
            throw new InvalidDataException($"Launcher package has no {InstallLocation.LauncherExe}.");
        return new LauncherZip(zipPath, files);
    }

    /// <summary>
    /// Unpacks every file into <paramref name="targetDir"/> (created if needed),
    /// verifying each one. <paramref name="onBytes"/> gets the running total of
    /// unpacked bytes. Throws <see cref="InvalidDataException"/> on damage.
    /// </summary>
    public void ExtractTo(string targetDir, Action<long>? onBytes, CancellationToken ct)
    {
        Directory.CreateDirectory(targetDir);
        long done = 0;
        var buffer = new byte[1 << 16];
        try
        {
            using var zip = OpenArchive(_path);
            foreach (var f in Files)
            {
                ct.ThrowIfCancellationRequested();
                var full = Path.Combine(targetDir, f.RelativePath.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(full)!);
                uint crc = 0;
                long written = 0;
                using (var src = zip.Entries[f.Index].Open())
                using (var dst = new FileStream(full, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16))
                {
                    int n;
                    while ((n = src.Read(buffer, 0, buffer.Length)) > 0)
                    {
                        ct.ThrowIfCancellationRequested();
                        written += n;
                        if (written > f.Size) throw new InvalidDataException($"Launcher package is damaged ({f.RelativePath} too long).");
                        crc = Crc32.Append(crc, buffer, 0, n);
                        dst.Write(buffer, 0, n);
                        done += n;
                        onBytes?.Invoke(done);
                    }
                }
                if (written != f.Size)
                    throw new InvalidDataException($"Launcher package is damaged ({f.RelativePath} is short).");
                if (f.Crc != null && crc != f.Crc)
                    throw new InvalidDataException($"Launcher package is damaged ({f.RelativePath} checksum).");
            }
        }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is EndOfStreamException)
        {
            throw new InvalidDataException("Launcher package is damaged: " + ex.Message, ex);
        }
    }

    // ZipArchive over a stream needs only System.IO.Compression on .NET
    // Framework (ZipFile lives in a second assembly there).
    private static ZipArchive OpenArchive(string path)
    {
        var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 16);
        try { return new ZipArchive(fs, ZipArchiveMode.Read); }
        catch { fs.Dispose(); throw; } // not a zip: don't leave the file locked (staging gets deleted next)
    }

    // A package path must stay inside the install folder.
    private static string CheckRelativePath(string rel)
    {
        if (string.IsNullOrEmpty(rel) || rel.StartsWith("/", StringComparison.Ordinal) || rel.Contains(":")
            || rel.Split('/').Any(p => p.Length == 0 || p == "." || p == "..")
            || rel.IndexOfAny(Path.GetInvalidPathChars()) >= 0)
            throw new InvalidDataException($"Launcher package has a bad path '{rel}'.");
        return rel;
    }

    private struct CentralRecord
    {
        public uint Crc;
        public uint Size; // uint.MaxValue = zip64 (real size elsewhere)
    }

    // The zip's own index (central directory), in entry order — the one place
    // the per-file CRC-32 can be read on .NET Framework. Null for zip64
    // archives (over 65535 files or 4 GB; the launcher is far from either).
    private static List<CentralRecord>? ReadCentralDirectory(FileStream fs)
    {
        const uint EndSig = 0x06054b50, EntrySig = 0x02014b50;
        int tailLen = (int)Math.Min(fs.Length, 22 + 65535);
        if (tailLen < 22) throw new InvalidDataException("Launcher package is damaged (not a zip).");
        var tail = new byte[tailLen];
        fs.Seek(-tailLen, SeekOrigin.End);
        ReadExactly(fs, tail, tailLen);
        int end = -1;
        for (int i = tailLen - 22; i >= 0; i--)
            if (BitConverter.ToUInt32(tail, i) == EndSig) { end = i; break; }
        if (end < 0) throw new InvalidDataException("Launcher package is damaged or incomplete (no zip index).");

        ushort count = BitConverter.ToUInt16(tail, end + 10);
        uint size = BitConverter.ToUInt32(tail, end + 12);
        uint offset = BitConverter.ToUInt32(tail, end + 16);
        if (count == ushort.MaxValue || size == uint.MaxValue || offset == uint.MaxValue) return null;
        if ((long)offset + size > fs.Length) throw new InvalidDataException("Launcher package is damaged or incomplete.");

        var dir = new byte[size];
        fs.Seek(offset, SeekOrigin.Begin);
        ReadExactly(fs, dir, dir.Length);
        var records = new List<CentralRecord>(count);
        int p = 0;
        for (int i = 0; i < count; i++)
        {
            if (p + 46 > dir.Length || BitConverter.ToUInt32(dir, p) != EntrySig)
                throw new InvalidDataException("Launcher package is damaged (zip index).");
            records.Add(new CentralRecord { Crc = BitConverter.ToUInt32(dir, p + 16), Size = BitConverter.ToUInt32(dir, p + 24) });
            p += 46 + BitConverter.ToUInt16(dir, p + 28) + BitConverter.ToUInt16(dir, p + 30) + BitConverter.ToUInt16(dir, p + 32);
        }
        return records;
    }

    private static void ReadExactly(Stream s, byte[] buffer, int count)
    {
        int read = 0;
        while (read < count)
        {
            int n = s.Read(buffer, read, count - read);
            if (n == 0) throw new InvalidDataException("Launcher package is incomplete.");
            read += n;
        }
    }
}
