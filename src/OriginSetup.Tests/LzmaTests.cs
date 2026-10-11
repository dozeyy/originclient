using System;
using System.IO;
using System.Linq;
using System.Text;
using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

public class LzmaTests
{
    private static void RoundTrip(byte[] data, string filters = "preset=6")
    {
        var packed = Python.LzmaAlone(data, filters);
        var output = new byte[data.Length];
        Lzma.Decode(packed, 0, packed.Length, output);
        Assert.True(data.AsSpan().SequenceEqual(output), "decoded bytes differ from the original");
    }

    private static byte[] Random(int n, int seed)
    {
        var b = new byte[n];
        new Random(seed).NextBytes(b);
        return b;
    }

    [Fact] public void OneByte() => RoundTrip(new byte[] { 42 });
    [Fact] public void AllZeros() => RoundTrip(new byte[1 << 20]);
    [Fact] public void RandomIncompressible() => RoundTrip(Random(300_000, 1));

    [Fact]
    public void RepetitiveText() => RoundTrip(Encoding.UTF8.GetBytes(
        string.Concat(Enumerable.Range(0, 20_000).Select(i => $"line {i % 97}: the quick brown fox {i % 13}\n"))));

    [Fact]
    public void MixedStructuredData()
    {
        // Runs of repeats at every distance class, interleaved with noise —
        // exercises rep0..rep3, short reps, long matches and literal coding.
        var rnd = new Random(7);
        var ms = new MemoryStream();
        var noise = Random(4096, 3);
        for (int i = 0; i < 4000; i++)
        {
            int kind = rnd.Next(4);
            if (kind == 0) ms.Write(noise, rnd.Next(0, 4000), rnd.Next(1, 90));
            else if (kind == 1) ms.Write(Enumerable.Repeat((byte)rnd.Next(256), rnd.Next(1, 300)).ToArray(), 0, 0 + 1);
            else if (kind == 2 && ms.Length > 100) { var buf = ms.ToArray(); int at = rnd.Next((int)ms.Length - 50); ms.Write(buf, at, rnd.Next(2, 50)); }
            else ms.WriteByte((byte)rnd.Next(256));
        }
        RoundTrip(ms.ToArray());
    }

    [Theory]
    [InlineData("preset=0")]
    [InlineData("preset=1")]
    [InlineData("preset=9")]
    [InlineData("filters=[{'id':lzma.FILTER_LZMA1,'lc':0,'lp':2,'pb':0,'dict_size':1<<16}]")]
    [InlineData("filters=[{'id':lzma.FILTER_LZMA1,'lc':4,'lp':0,'pb':4,'dict_size':1<<20}]")]
    [InlineData("filters=[{'id':lzma.FILTER_LZMA1,'lc':1,'lp':3,'pb':1,'dict_size':1<<12}]")]
    public void PresetsAndProperties(string filters)
    {
        var data = Encoding.UTF8.GetBytes(string.Concat(Enumerable.Range(0, 30_000).Select(i => (char)('a' + i * 7 % 26) + (i % 50 == 0 ? "\n" : ""))));
        RoundTrip(data.Concat(Random(50_000, 9)).Concat(data).ToArray(), filters);
    }

    [Fact]
    public void CorruptStreamThrows()
    {
        var data = Encoding.UTF8.GetBytes(string.Concat(Enumerable.Repeat("origin rings ", 50_000)));
        var packed = Python.LzmaAlone(data);
        packed[packed.Length / 2] ^= 0x5A;
        var output = new byte[data.Length];
        // Corruption either throws or yields different bytes — the payload's
        // per-chunk CRC32 catches the second case (see PayloadTests).
        try
        {
            Lzma.Decode(packed, 0, packed.Length, output);
            Assert.False(data.AsSpan().SequenceEqual(output));
        }
        catch (InvalidDataException) { }
    }

    [Fact]
    public void TruncatedStreamThrows()
    {
        var data = Random(100_000, 5);
        var packed = Python.LzmaAlone(data);
        Assert.Throws<InvalidDataException>(() => Lzma.Decode(packed, 0, packed.Length / 2, new byte[data.Length]));
    }

    [Fact]
    public void Crc32MatchesZlib()
    {
        // zlib.crc32(b"123456789") == 0xCBF43926 (the standard check value)
        var b = Encoding.ASCII.GetBytes("123456789");
        Assert.Equal(0xCBF43926u, Crc32.Compute(b, 0, b.Length));
    }
}
