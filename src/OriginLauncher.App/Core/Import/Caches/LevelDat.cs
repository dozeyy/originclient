using System.Buffers.Binary;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace OriginLauncher.App.Core.Import.Caches;

/// <summary>What makes two worlds "the same world" for cache purposes: the name and the seed.</summary>
public sealed record WorldIdentity(string? LevelName, long? Seed)
{
    /// <summary>Both must be known on both sides and equal: a guess that two worlds match could pair a cache with the wrong terrain.</summary>
    public bool Matches(WorldIdentity? other) =>
        other != null && LevelName != null && Seed != null && LevelName == other.LevelName && Seed == other.Seed;
}

// A deliberately tiny, read-only reader for a world's level.dat (gzip-compressed NBT): just
// Data.LevelName and the seed — Data.WorldGenSettings.seed from 1.16 on, Data.RandomSeed before.
// Bounded so a hostile file can't hurt: at most 16 MB inflated, nesting at most 32 deep, every
// length checked against what is left in the buffer. Anything unexpected is "unknown" (null).
public static class LevelDat
{
    private const int MaxInflatedBytes = 16 * 1024 * 1024;
    private const int MaxDepth = 32;

    public static WorldIdentity? Read(string path, SourceFs fs)
    {
        using var raw = fs.OpenRead(path);
        if (raw == null) return null;
        try
        {
            return Parse(Inflate(raw));
        }
        catch (Exception ex) when (ex is InvalidDataException or IOException or FormatException)
        {
            return null;
        }
    }

    public static WorldIdentity? Parse(byte[] nbt)
    {
        try
        {
            var reader = new Cursor(nbt);
            if (reader.Byte() != 10) return null;
            reader.SkipString();                  // the root compound's (empty) name
            string? name = null;
            long? seed = null, legacySeed = null;
            Entries(reader, 0, (tag, label) =>
            {
                if (tag != 10 || label != "Data") return false;
                Entries(reader, 1, (t, l) =>
                {
                    switch (t, l)
                    {
                        case (8, "LevelName"): name = reader.String(); return true;
                        case (4, "RandomSeed"): legacySeed = reader.Long(); return true;
                        case (10, "WorldGenSettings"):
                            Entries(reader, 2, (t2, l2) =>
                            {
                                if (t2 != 4 || l2 != "seed") return false;
                                seed = reader.Long();
                                return true;
                            });
                            return true;
                        default: return false;
                    }
                });
                return true;
            });
            return new WorldIdentity(name, seed ?? legacySeed);
        }
        catch (Exception ex) when (ex is FormatException or OverflowException or ArgumentException)
        {
            return null;
        }
    }

    // Walks one compound's entries to its end tag. The handler consumes the payload and returns true,
    // or returns false and the payload is skipped.
    private static void Entries(Cursor c, int depth, Func<int, string, bool> handler)
    {
        if (depth > MaxDepth) throw new FormatException("nested too deep");
        while (true)
        {
            var tag = c.Byte();
            if (tag == 0) return;
            var label = c.String();
            if (!handler(tag, label)) c.SkipPayload(tag, depth + 1);
        }
    }

    private static byte[] Inflate(Stream compressed)
    {
        using var gzip = new GZipStream(compressed, CompressionMode.Decompress);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        int n;
        while ((n = gzip.Read(buffer, 0, buffer.Length)) > 0)
        {
            if (output.Length + n > MaxInflatedBytes) throw new InvalidDataException("level.dat is far bigger than any real one");
            output.Write(buffer, 0, n);
        }
        return output.ToArray();
    }

    private sealed class Cursor
    {
        private readonly byte[] _d;
        private int _p;

        public Cursor(byte[] d) => _d = d;

        private ReadOnlySpan<byte> Take(int count)
        {
            if (count < 0 || count > _d.Length - _p) throw new FormatException("truncated");
            var span = new ReadOnlySpan<byte>(_d, _p, count);
            _p += count;
            return span;
        }

        public int Byte() => Take(1)[0];
        public long Long() => BinaryPrimitives.ReadInt64BigEndian(Take(8));
        private int Int() => BinaryPrimitives.ReadInt32BigEndian(Take(4));
        private int Short() => BinaryPrimitives.ReadUInt16BigEndian(Take(2));

        public string String() => Encoding.UTF8.GetString(Take(Short()));
        public void SkipString() => Take(Short());

        public void SkipPayload(int tag, int depth)
        {
            if (depth > MaxDepth) throw new FormatException("nested too deep");
            switch (tag)
            {
                case 1: Take(1); break;
                case 2: Take(2); break;
                case 3: case 5: Take(4); break;
                case 4: case 6: Take(8); break;
                case 7: Take(Int()); break;
                case 8: SkipString(); break;
                case 9:
                    var elementTag = Byte();
                    var count = Int();
                    if (count < 0) throw new FormatException("negative list");
                    if (elementTag == 0) { if (count > 0) throw new FormatException("bad list"); break; }
                    for (var i = 0; i < count; i++) SkipPayload(elementTag, depth + 1);
                    break;
                case 10:
                    while (true)
                    {
                        var t = Byte();
                        if (t == 0) break;
                        SkipString();
                        SkipPayload(t, depth + 1);
                    }
                    break;
                case 11: Take(checked(Int() * 4)); break;
                case 12: Take(checked(Int() * 8)); break;
                default: throw new FormatException("unknown tag");
            }
        }
    }
}
