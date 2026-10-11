using System;

namespace OriginSetup.Engine;

// Standard CRC-32 (IEEE 802.3, the zlib/zip polynomial) — matches Python's
// zlib.crc32, which tools/installer/pack_payload.py stores per payload chunk.
// .NET Framework 4.7.2 has no built-in CRC32. Guards against a damaged
// download installing a broken launcher: LZMA-alone streams carry no checksum.
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

    public static uint Compute(byte[] data, int offset, int count)
    {
        uint crc = 0xFFFFFFFFu;
        for (int i = offset; i < offset + count; i++)
            crc = Table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
        return crc ^ 0xFFFFFFFFu;
    }
}
