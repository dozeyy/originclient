using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;
using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// The binary patches inside a small self-update (UpdateDelta.ApplyPatch, format ODPATCH1 in tools/release/delta.py):
// a changed file rebuilt from its installed copy plus only the bytes that changed. Any damaged or mismatched patch, and
// any installed file that isn't the one the patch was made from, must answer "use the full zip" with an empty stage.
//
// Fixtures/Delta/patch-from-1.0.1-to-1.0.2.zip is made by the real release builder (delta.py build) from
//   1.0.1: OriginLauncher.App.exe = Base, readme.txt = "unchanged"
//   1.0.2: OriginLauncher.App.exe = Patched, readme.txt = "unchanged"
// so it also proves the Python encoder and this applier agree on the format.
public sealed class UpdatePatchTests : IDisposable
{
    private const string Exe = "OriginLauncher.App.exe";
    private const string PatchEntry = "patches/" + Exe;
    private static readonly Version V1 = new(1, 0, 1, 0);
    private static readonly Version V2 = new(1, 0, 2, 0);

    // The old exe: 64 KB of a fixed pattern (the same formula as the fixture script).
    private static readonly byte[] Base = Enumerable.Range(0, 65536).Select(i => (byte)((i * 131) ^ (i >> 7))).ToArray();
    // The new exe: 16 bytes inserted at 20000, 1000 bytes removed at 40000 (old offsets), everything after shifted.
    private static readonly byte[] Patched =
        [.. Base[..20000], .. "patched in 1.0.2"u8.ToArray(), .. Base[20000..40000], .. Base[41000..]];

    private readonly string _root = Path.Combine(Path.GetTempPath(), "origin-patch-" + Guid.NewGuid().ToString("N"));
    private readonly string _installed;
    private readonly string _stage;

    public UpdatePatchTests()
    {
        _installed = Path.Combine(_root, "installed");
        _stage = Path.Combine(_root, "updates", "1.0.2");
        Directory.CreateDirectory(_installed);
        File.WriteAllBytes(Path.Combine(_installed, Exe), Base);
        File.WriteAllText(Path.Combine(_installed, "readme.txt"), "unchanged");
        Directory.CreateDirectory(_stage);
    }

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private static string Fixture => Path.Combine(AppContext.BaseDirectory, "Fixtures", "Delta", "patch-from-1.0.1-to-1.0.2.zip");

    // ---- through the real delta ----

    [Fact]
    public void A_patch_built_by_the_release_script_rebuilds_the_changed_file_exactly()
    {
        using (var zip = ZipFile.OpenRead(Fixture))
            Assert.NotNull(zip.GetEntry(PatchEntry)); // the fixture really carries a patch, not the whole file

        Assert.True(UpdateDelta.TryStage(Fixture, _installed, _stage, V1, V2, out var failure), failure);

        Assert.Equal(Patched, File.ReadAllBytes(Path.Combine(_stage, Exe)));
        Assert.Equal("unchanged", File.ReadAllText(Path.Combine(_stage, "readme.txt")));
        Assert.Equal(Base, File.ReadAllBytes(Path.Combine(_installed, Exe)));
    }

    [Fact]
    public void An_installed_file_with_other_bytes_but_the_same_size_falls_back_on_the_manifest_hash()
    {
        // The patch applies (same size, copies stay inside the file), so only the manifest's SHA-256 can catch this.
        var changed = (byte[])Base.Clone();
        changed[30000] ^= 0xFF;
        File.WriteAllBytes(Path.Combine(_installed, Exe), changed);
        AssertFallsBack(Fixture);
    }

    [Fact]
    public void An_installed_file_of_another_size_falls_back()
    {
        File.WriteAllBytes(Path.Combine(_installed, Exe), [.. Base, 0]);
        AssertFallsBack(Fixture);
    }

    [Fact]
    public void An_installed_file_that_is_missing_falls_back()
    {
        File.Delete(Path.Combine(_installed, Exe));
        AssertFallsBack(Fixture);
    }

    [Theory]
    [InlineData("flip a byte")]
    [InlineData("cut short")]
    [InlineData("extra byte")]
    [InlineData("not a patch")]
    public void A_damaged_patch_in_the_delta_falls_back(string damage)
    {
        var delta = Path.Combine(_root, "damaged.zip");
        File.Copy(Fixture, delta);
        using (var zip = ZipFile.Open(delta, ZipArchiveMode.Update))
        {
            var entry = zip.GetEntry(PatchEntry)!;
            byte[] patch;
            using (var s = entry.Open()) { using var m = new MemoryStream(); s.CopyTo(m); patch = m.ToArray(); }
            patch = damage switch
            {
                // The inserted text travels as a LITERAL, so this byte still applies; the manifest hash refuses it.
                "flip a byte" => Flip(patch, IndexOf(patch, "patched in 1.0.2"u8.ToArray())),
                "cut short" => patch[..^1],
                "extra byte" => [.. patch, (byte)'E'],
                _ => "hello"u8.ToArray(),
            };
            entry.Delete();
            using var w = zip.CreateEntry(PatchEntry).Open();
            w.Write(patch);
        }
        AssertFallsBack(delta);
    }

    [Fact]
    public void A_path_carried_both_whole_and_as_a_patch_is_refused()
    {
        var delta = Path.Combine(_root, "both.zip");
        File.Copy(Fixture, delta);
        using (var zip = ZipFile.Open(delta, ZipArchiveMode.Update))
        using (var w = zip.CreateEntry("files/" + Exe).Open())
            w.Write(Patched);
        AssertFallsBack(delta);
    }

    // ---- the applier on its own ----

    [Fact]
    public void Copies_and_literals_are_written_in_order()
    {
        var base10 = "0123456789"u8.ToArray();
        var patch = Patch(10, 8, Copy(2, 3), Literal("abc"), Copy(0, 2), End());

        Assert.Equal("234abc01", Encoding.ASCII.GetString(Apply(patch, base10, 8)));
    }

    public static TheoryData<string, byte[], long> DamagedPatches => new()
    {
        { "wrong magic", [.. "ODPATCH2"u8.ToArray(), .. Patch(10, 3, Copy(0, 3), End())[8..]], 3 },
        { "made from another base size", Patch(11, 3, Copy(0, 3), End()), 3 },
        { "result size not the manifest's", Patch(10, 4, Copy(0, 4), End()), 3 },
        { "copy past the base", Patch(10, 3, Copy(8, 3), End()), 3 },
        { "copy offset past the base", Patch(10, 1, Copy(ulong.MaxValue, 1), End()), 1 },
        { "empty copy", Patch(10, 3, Copy(0, 0), Copy(0, 3), End()), 3 },
        { "empty literal", Patch(10, 3, Literal(""), Copy(0, 3), End()), 3 },
        { "literal past the result size", Patch(10, 3, Literal("abcd"), End()), 3 },
        { "copy past the result size", Patch(10, 3, Copy(0, 4), End()), 3 },
        { "too short a result", Patch(10, 3, Copy(0, 2), End()), 3 },
        { "unknown op", Patch(10, 3, [(byte)'X'], Copy(0, 3), End()), 3 },
        { "no end op", Patch(10, 3, Copy(0, 3)), 3 },
        { "bytes after the end op", Patch(10, 3, Copy(0, 3), End(), [1]), 3 },
        { "literal cut short", Patch(10, 3, Literal("abc")[..^1]), 3 },
        { "header cut short", "ODPATCH1"u8.ToArray()[..5], 3 },
    };

    [Theory]
    [MemberData(nameof(DamagedPatches))]
    public void A_damaged_patch_is_refused(string what, byte[] patch, long expectedSize)
    {
        var ex = Record.Exception(() => Apply(patch, "0123456789"u8.ToArray(), expectedSize));
        Assert.True(ex is UpdateDeltaException, $"{what}: {ex?.GetType().Name ?? "no exception"}");
    }

    // ---- helpers ----

    private void AssertFallsBack(string delta)
    {
        var before = Snapshot(_installed);
        Assert.False(UpdateDelta.TryStage(delta, _installed, _stage, V1, V2, out var failure));
        Assert.False(string.IsNullOrEmpty(failure));
        Assert.Empty(Directory.EnumerateFileSystemEntries(_stage));
        Assert.Equal(before, Snapshot(_installed));
    }

    private static Dictionary<string, string> Snapshot(string root) =>
        Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
            .ToDictionary(f => Path.GetRelativePath(root, f), f => Convert.ToHexString(File.ReadAllBytes(f)));

    private static byte[] Apply(byte[] patch, byte[] baseBytes, long expectedSize)
    {
        using var output = new MemoryStream();
        UpdateDelta.ApplyPatch(new MemoryStream(patch), new MemoryStream(baseBytes), output, expectedSize);
        return output.ToArray();
    }

    private static byte[] Patch(ulong baseSize, ulong targetSize, params byte[][] ops)
    {
        var header = new byte[24];
        "ODPATCH1"u8.CopyTo(header);
        BinaryPrimitives.WriteUInt64LittleEndian(header.AsSpan(8), baseSize);
        BinaryPrimitives.WriteUInt64LittleEndian(header.AsSpan(16), targetSize);
        return [.. header, .. ops.SelectMany(o => o)];
    }

    private static byte[] Copy(ulong offset, uint length)
    {
        var op = new byte[13];
        op[0] = (byte)'C';
        BinaryPrimitives.WriteUInt64LittleEndian(op.AsSpan(1), offset);
        BinaryPrimitives.WriteUInt32LittleEndian(op.AsSpan(9), length);
        return op;
    }

    private static byte[] Literal(string text)
    {
        var bytes = Encoding.ASCII.GetBytes(text);
        var op = new byte[5];
        op[0] = (byte)'L';
        BinaryPrimitives.WriteUInt32LittleEndian(op.AsSpan(1), (uint)bytes.Length);
        return [.. op, .. bytes];
    }

    private static byte[] End() => [(byte)'E'];

    private static byte[] Flip(byte[] data, int index)
    {
        Assert.True(index >= 0, "the literal text is not in the patch");
        var copy = (byte[])data.Clone();
        copy[index] ^= 0x20;
        return copy;
    }

    private static int IndexOf(byte[] data, byte[] needle) => data.AsSpan().IndexOf(needle);
}
