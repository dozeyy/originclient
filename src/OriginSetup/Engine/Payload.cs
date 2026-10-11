using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace OriginSetup.Engine;

// The launcher files packed into setup.exe ("OPK1"), written by
// tools/installer/pack_payload.py — keep the two in step.
//
// Layout of OriginLauncher-Setup.exe:
//   [OriginSetup.exe as compiled][payload][trailer]
//   trailer = int64 payloadOffset + ASCII "OPKEND01" (16 bytes, at file end)
// Windows ignores bytes appended after a PE image, so the exe runs normally,
// and the first payloadOffset bytes are the bare setup app — copied into the
// install folder as the (small) uninstaller.
//
// Payload (little-endian):
//   "OPK1", int32 formatVersion (1), string appVersion,
//   int32 fileCount, then per file: string relativePath ('/'-separated), int64 size
//   int32 chunkCount, then per chunk: int64 rawSize, int64 packedSize, uint32 crc32(raw)
//   chunk data: each chunk is an independent .lzma stream
//   (string = int32 byte length + UTF-8)
// The files are laid end to end and that byte run is cut into fixed-size
// chunks, so chunks decode in parallel and may span file boundaries.
public sealed class Payload
{
    public const string TrailerMagic = "OPKEND01";
    private const string Magic = "OPK1";
    private const int FormatVersion = 1;

    public sealed class Entry
    {
        public string RelativePath { get; }
        public long Size { get; }
        internal long Start { get; } // offset in the concatenated file run

        internal Entry(string relativePath, long size, long start)
        {
            RelativePath = relativePath;
            Size = size;
            Start = start;
        }
    }

    private sealed class Chunk
    {
        public long RawStart;
        public long RawSize;
        public long PackedOffset; // absolute, in the setup exe
        public long PackedSize;
        public uint Crc;
    }

    private readonly string _path;
    private readonly List<Chunk> _chunks;

    /// <summary>Launcher version the payload carries ("1.0.40").</summary>
    public string AppVersion { get; }
    public IReadOnlyList<Entry> Files { get; }
    public long TotalSize { get; }
    /// <summary>Where the payload starts = length of the bare setup app.</summary>
    public long PayloadOffset { get; }

    private Payload(string path, long payloadOffset, string appVersion, List<Entry> files, List<Chunk> chunks)
    {
        _path = path;
        PayloadOffset = payloadOffset;
        AppVersion = appVersion;
        Files = files;
        _chunks = chunks;
        TotalSize = files.Sum(f => f.Size);
    }

    /// <summary>
    /// Reads the payload appended to <paramref name="exePath"/>, or null when
    /// the file has none (the uninstaller copy, or a bare dev build).
    /// </summary>
    public static Payload? TryOpen(string exePath)
    {
        using var fs = new FileStream(exePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (fs.Length < 16) return null;
        fs.Seek(-16, SeekOrigin.End);
        var trailer = new byte[16];
        ReadExactly(fs, trailer, 16);
        if (Encoding.ASCII.GetString(trailer, 8, 8) != TrailerMagic) return null;
        long offset = BitConverter.ToInt64(trailer, 0);
        if (offset <= 0 || offset >= fs.Length - 16)
            throw new InvalidDataException("Setup file is damaged (bad payload offset).");

        fs.Seek(offset, SeekOrigin.Begin);
        var r = new BinaryReader(fs, Encoding.UTF8);
        if (Encoding.ASCII.GetString(r.ReadBytes(4)) != Magic)
            throw new InvalidDataException("Setup file is damaged (bad payload header).");
        int version = r.ReadInt32();
        if (version != FormatVersion)
            throw new InvalidDataException($"Unsupported payload format {version}.");
        string appVersion = ReadString(r);

        int fileCount = r.ReadInt32();
        if (fileCount < 1 || fileCount > 100_000) throw new InvalidDataException("Setup file is damaged (file count).");
        var files = new List<Entry>(fileCount);
        long start = 0;
        for (int i = 0; i < fileCount; i++)
        {
            string rel = ReadString(r);
            long size = r.ReadInt64();
            if (size < 0) throw new InvalidDataException("Setup file is damaged (file size).");
            files.Add(new Entry(CheckRelativePath(rel), size, start));
            start += size;
        }

        int chunkCount = r.ReadInt32();
        if (chunkCount < 0 || chunkCount > 100_000) throw new InvalidDataException("Setup file is damaged (chunk count).");
        var chunks = new List<Chunk>(chunkCount);
        long rawStart = 0;
        for (int i = 0; i < chunkCount; i++)
        {
            var c = new Chunk { RawStart = rawStart, RawSize = r.ReadInt64(), PackedSize = r.ReadInt64(), Crc = r.ReadUInt32() };
            if (c.RawSize <= 0 || c.RawSize > int.MaxValue || c.PackedSize <= 0 || c.PackedSize > int.MaxValue)
                throw new InvalidDataException("Setup file is damaged (chunk size).");
            rawStart += c.RawSize;
            chunks.Add(c);
        }
        if (rawStart != start)
            throw new InvalidDataException("Setup file is damaged (sizes disagree).");

        long packed = fs.Position;
        foreach (var c in chunks)
        {
            c.PackedOffset = packed;
            packed += c.PackedSize;
        }
        if (packed != fs.Length - 16)
            throw new InvalidDataException("Setup file is damaged or incomplete.");

        return new Payload(exePath, offset, appVersion, files, chunks);
    }

    /// <summary>
    /// Unpacks every file into <paramref name="targetDir"/> (created if needed).
    /// <paramref name="onBytes"/> receives a running total of unpacked bytes,
    /// from worker threads. Chunks decode in parallel, up to maxParallel.
    /// </summary>
    public void ExtractTo(string targetDir, Action<long>? onBytes, CancellationToken ct, int maxParallel = 4)
    {
        Directory.CreateDirectory(targetDir);
        var fullPaths = new string[Files.Count];
        for (int i = 0; i < Files.Count; i++)
        {
            var full = Path.Combine(targetDir, Files[i].RelativePath.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(full)!);
            using (var f = new FileStream(full, FileMode.Create, FileAccess.Write, FileShare.ReadWrite))
                f.SetLength(Files[i].Size);
            fullPaths[i] = full;
        }

        long done = 0;
        var options = new ParallelOptions
        {
            CancellationToken = ct,
            MaxDegreeOfParallelism = Math.Max(1, Math.Min(maxParallel, Environment.ProcessorCount)),
        };

        Parallel.ForEach(_chunks, options, chunk =>
        {
            var packed = new byte[chunk.PackedSize];
            using (var fs = new FileStream(_path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                fs.Seek(chunk.PackedOffset, SeekOrigin.Begin);
                ReadExactly(fs, packed, packed.Length);
            }

            var raw = new byte[chunk.RawSize];
            int reported = 0;
            Lzma.Decode(packed, 0, packed.Length, raw, produced =>
            {
                ct.ThrowIfCancellationRequested();
                onBytes?.Invoke(Interlocked.Add(ref done, produced - reported));
                reported = produced;
            });
            if (Crc32.Compute(raw, 0, raw.Length) != chunk.Crc)
                throw new InvalidDataException("Setup file is damaged (checksum mismatch) — download it again.");

            WriteRange(fullPaths, chunk.RawStart, raw);
            onBytes?.Invoke(Interlocked.Add(ref done, raw.Length - reported));
        });
    }

    // Writes one decoded chunk to every file it overlaps.
    private void WriteRange(string[] fullPaths, long rawStart, byte[] raw)
    {
        long rawEnd = rawStart + raw.Length;
        for (int i = 0; i < Files.Count; i++)
        {
            var f = Files[i];
            long fStart = f.Start, fEnd = f.Start + f.Size;
            if (fEnd <= rawStart || fStart >= rawEnd || f.Size == 0) continue;
            long from = Math.Max(fStart, rawStart), to = Math.Min(fEnd, rawEnd);
            using var fs = new FileStream(fullPaths[i], FileMode.Open, FileAccess.Write, FileShare.ReadWrite);
            fs.Seek(from - fStart, SeekOrigin.Begin);
            fs.Write(raw, (int)(from - rawStart), (int)(to - from));
        }
    }

    // A payload path must stay inside the install folder.
    private static string CheckRelativePath(string rel)
    {
        if (string.IsNullOrEmpty(rel) || rel.StartsWith("/") || rel.Contains(":") || rel.Contains("\\")
            || rel.Split('/').Any(p => p.Length == 0 || p == "." || p == ".."))
            throw new InvalidDataException($"Setup file is damaged (bad path '{rel}').");
        return rel;
    }

    private static string ReadString(BinaryReader r)
    {
        int len = r.ReadInt32();
        if (len < 0 || len > 4096) throw new InvalidDataException("Setup file is damaged (string length).");
        return Encoding.UTF8.GetString(r.ReadBytes(len));
    }

    private static void ReadExactly(Stream s, byte[] buffer, int count)
    {
        int read = 0;
        while (read < count)
        {
            int n = s.Read(buffer, read, count - read);
            if (n == 0) throw new EndOfStreamException("Setup file is incomplete — download it again.");
            read += n;
        }
    }
}
