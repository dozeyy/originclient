using System.Buffers.Binary;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace OriginLauncher.App.Core.Import;

/// <summary>One saved server, as a servers.dat holds it: the name and address for matching, and the entry's own NBT bytes.</summary>
/// <param name="Payload">The entry's compound payload exactly as the source wrote it (its tags and the closing End byte):
/// copied byte for byte, so an icon, "acceptTextures" or a field a newer version adds survives the merge.</param>
public sealed record ServerEntry(string Name, string Address, byte[] Payload);

/// <summary>
/// The server list an import brings into one Origin game folder: every contributing setup's entries in the order they
/// were planned (the settings setup first), one op to write them (DestinationLedger.Servers holds it per folder).
/// </summary>
public sealed class ServerMerge
{
    public ServerMerge(IEnumerable<string> alreadyInOrigin)
    {
        foreach (var address in alreadyInOrigin) Keys.Add(ServerList.AddressKey(address));
    }

    /// <summary>Address keys already in Origin's list or already coming from an earlier setup of this run.</summary>
    public HashSet<string> Keys { get; } = new(StringComparer.Ordinal);

    /// <summary>What comes over, in order (each address once).</summary>
    public List<ServerEntry> Entries { get; } = new();

    /// <summary>The setup key whose plan holds the merge op (later setups hold stand-ins for it).</summary>
    public string? Owner { get; set; }
}

// servers.dat is an uncompressed NBT file: a root compound holding a list "servers" of compounds (name, ip, icon,
// acceptTextures, hidden...). Origin never builds an entry itself: it reads the address to match on and moves each
// entry's bytes as they are. The player's own list is never dropped or reordered; imported entries are APPENDED, each
// address once (AddressKey: "MC.Example.net:25565" and "mc.example.net" are the same server).
//
// Strings stay raw bytes end to end: Java writes "modified UTF-8" (an emoji is two 3-byte halves), which a plain UTF-8
// decode would turn into replacement characters - rewriting the player's own list must never damage a name.
public static class ServerList
{
    private const byte End = 0, Byte = 1, Short = 2, Int = 3, Long = 4, Float = 5, Double = 6, ByteArray = 7,
        String = 8, List = 9, Compound = 10, IntArray = 11, LongArray = 12;

    /// <summary>The form two addresses are compared in: trimmed, lower case, no trailing dot, no default port.</summary>
    public static string AddressKey(string address)
    {
        var key = address.Trim().ToLowerInvariant();
        if (key.EndsWith(":25565", StringComparison.Ordinal)) key = key[..^":25565".Length];
        return key.TrimEnd('.');
    }

    /// <summary>The entries of a servers.dat (gzip is tolerated); null when the bytes are not a server list.</summary>
    public static List<ServerEntry>? Read(byte[]? bytes)
    {
        if (bytes == null) return null;
        try
        {
            var root = ParseRoot(Unzip(bytes));
            return root?.Entries;
        }
        catch (Exception ex) when (ex is InvalidDataException or ArgumentOutOfRangeException or IndexOutOfRangeException or IOException)
        {
            return null;
        }
    }

    /// <summary>
    /// <paramref name="existing"/> (Origin's list; null = none yet) with <paramref name="imported"/> appended, each address
    /// once and none already there. Returns null when the existing file is not a server list: it is then left untouched.
    /// </summary>
    public static byte[]? Merge(byte[]? existing, IEnumerable<ServerEntry> imported, out int added)
    {
        added = 0;
        Root root;
        if (existing == null || existing.Length == 0) root = new Root(Array.Empty<byte>(), new List<RawTag>(), new List<ServerEntry>());
        else
        {
            try { root = ParseRoot(existing) ?? throw new InvalidDataException(); }
            catch (Exception ex) when (ex is InvalidDataException or ArgumentOutOfRangeException or IndexOutOfRangeException or IOException)
            {
                return null;
            }
        }
        var keys = new HashSet<string>(root.Entries.Select(e => AddressKey(e.Address)), StringComparer.Ordinal);
        var entries = root.Entries.ToList();
        foreach (var entry in imported)
        {
            var key = AddressKey(entry.Address);
            if (key.Length == 0 || !keys.Add(key)) continue;
            entries.Add(entry);
            added++;
        }
        return Write(root with { Entries = entries });
    }

    // ---- the format ----

    private sealed record RawTag(byte Type, byte[] Name, byte[] Payload);

    // The root's name (raw), every root tag except "servers" (kept as they are), and the server entries.
    private sealed record Root(byte[] Name, List<RawTag> Others, List<ServerEntry> Entries);

    private static byte[] Unzip(byte[] bytes)
    {
        if (bytes.Length < 2 || bytes[0] != 0x1f || bytes[1] != 0x8b) return bytes;
        using var input = new GZipStream(new MemoryStream(bytes), CompressionMode.Decompress);
        using var output = new MemoryStream();
        input.CopyTo(output);
        return output.ToArray();
    }

    private static Root? ParseRoot(byte[] data)
    {
        var pos = 0;
        if (data.Length < 3 || data[pos++] != Compound) return null;
        var rootName = ReadName(data, ref pos);
        var others = new List<RawTag>();
        var entries = new List<ServerEntry>();
        var found = false;
        while (true)
        {
            var type = At(data, pos++);
            if (type == End) break;
            var name = ReadName(data, ref pos);
            var start = pos;
            Skip(data, type, ref pos, 0);
            if (!found && type == List && Encoding.UTF8.GetString(name) == "servers")
            {
                found = true;
                var p = start;
                var elementType = At(data, p++);
                var count = ReadInt(data, ref p);
                if (count < 0) throw new InvalidDataException();
                if (count > 0 && elementType != Compound) throw new InvalidDataException();
                for (var i = 0; i < count; i++)
                {
                    var entryStart = p;
                    Skip(data, Compound, ref p, 1);
                    entries.Add(Entry(data[entryStart..p]));
                }
                continue;
            }
            others.Add(new RawTag(type, name, data[start..pos]));
        }
        return new Root(rootName, others, entries);
    }

    // One entry's name and ip, read out of its compound payload.
    private static ServerEntry Entry(byte[] payload)
    {
        string name = "", ip = "";
        var pos = 0;
        while (true)
        {
            var type = At(payload, pos++);
            if (type == End) break;
            var tagName = Encoding.UTF8.GetString(ReadName(payload, ref pos));
            if (type == String)
            {
                var value = Encoding.UTF8.GetString(ReadName(payload, ref pos));
                if (tagName == "ip") ip = value;
                else if (tagName == "name") name = value;
                continue;
            }
            Skip(payload, type, ref pos, 1);
        }
        return new ServerEntry(name, ip, payload);
    }

    private static byte[] Write(Root root)
    {
        using var ms = new MemoryStream();
        ms.WriteByte(Compound);
        WriteName(ms, root.Name);
        foreach (var tag in root.Others)
        {
            ms.WriteByte(tag.Type);
            WriteName(ms, tag.Name);
            ms.Write(tag.Payload);
        }
        ms.WriteByte(List);
        WriteName(ms, Encoding.UTF8.GetBytes("servers"));
        ms.WriteByte(root.Entries.Count == 0 ? End : Compound);
        Span<byte> count = stackalloc byte[4];
        BinaryPrimitives.WriteInt32BigEndian(count, root.Entries.Count);
        ms.Write(count);
        foreach (var entry in root.Entries) ms.Write(entry.Payload);
        ms.WriteByte(End);
        return ms.ToArray();
    }

    private static byte At(byte[] data, int pos) =>
        pos >= 0 && pos < data.Length ? data[pos] : throw new InvalidDataException("servers.dat ends early");

    private static byte[] ReadName(byte[] data, ref int pos)
    {
        if (pos + 2 > data.Length) throw new InvalidDataException();
        var length = BinaryPrimitives.ReadUInt16BigEndian(data.AsSpan(pos, 2));
        pos += 2;
        if (pos + length > data.Length) throw new InvalidDataException();
        var name = data[pos..(pos + length)];
        pos += length;
        return name;
    }

    private static void WriteName(Stream s, byte[] name)
    {
        Span<byte> length = stackalloc byte[2];
        BinaryPrimitives.WriteUInt16BigEndian(length, (ushort)name.Length);
        s.Write(length);
        s.Write(name);
    }

    private static int ReadInt(byte[] data, ref int pos)
    {
        if (pos + 4 > data.Length) throw new InvalidDataException();
        var value = BinaryPrimitives.ReadInt32BigEndian(data.AsSpan(pos, 4));
        pos += 4;
        return value;
    }

    private static void Advance(byte[] data, ref int pos, long bytes)
    {
        if (bytes < 0 || pos + bytes > data.Length) throw new InvalidDataException();
        pos += (int)bytes;
    }

    // Moves past one payload of <type>. Depth-limited: a crafted file can't recurse the importer off its stack.
    private static void Skip(byte[] data, byte type, ref int pos, int depth)
    {
        if (depth > 64) throw new InvalidDataException();
        switch (type)
        {
            case Byte: Advance(data, ref pos, 1); break;
            case Short: Advance(data, ref pos, 2); break;
            case Int: case Float: Advance(data, ref pos, 4); break;
            case Long: case Double: Advance(data, ref pos, 8); break;
            case ByteArray: Advance(data, ref pos, ReadInt(data, ref pos)); break;
            case IntArray: Advance(data, ref pos, 4L * ReadInt(data, ref pos)); break;
            case LongArray: Advance(data, ref pos, 8L * ReadInt(data, ref pos)); break;
            case String: ReadName(data, ref pos); break;
            case List:
            {
                var elementType = At(data, pos++);
                var count = ReadInt(data, ref pos);
                if (count < 0) throw new InvalidDataException();
                for (var i = 0; i < count; i++) Skip(data, elementType, ref pos, depth + 1);
                break;
            }
            case Compound:
                while (true)
                {
                    var inner = At(data, pos++);
                    if (inner == End) break;
                    ReadName(data, ref pos);
                    Skip(data, inner, ref pos, depth + 1);
                }
                break;
            default: throw new InvalidDataException($"unknown tag {type}");
        }
    }
}
