using System;

namespace OriginSetup.Engine;

// Standard CRC-32 (IEEE 802.3, the zip/zlib polynomial) — the checksum every
// zip entry carries. .NET Framework 4.7.2 has no built-in CRC32 and its
// ZipArchive never checks it, so LauncherZip verifies each file with this:
// deflate itself carries no checksum, so without it a damaged download could
// unpack into a broken launcher.
public static class Crc32
{
    private static readonly uint[] Table = BuildTable();

    private static uint[] BuildTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint c = i;
            for (int k = 0; k < 8; k++)
                c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        return table;
    }

    public static uint Compute(byte[] data, int offset, int count) => Append(0, data, offset, count);

    /// <summary>
    /// Continues <paramref name="crc"/> (the CRC of everything before) over more
    /// bytes, like zlib.crc32(data, crc). Start from 0.
    /// </summary>
    public static uint Append(uint crc, byte[] data, int offset, int count)
    {
        uint c = ~crc;
        for (int i = offset; i < offset + count; i++)
            c = Table[(c ^ data[i]) & 0xFF] ^ (c >> 8);
        return ~c;
    }
}
