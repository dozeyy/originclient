using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace OriginLauncher.App.Core.Sync;

// Settings Sync's memory: one JSON file under the launcher's user data
// (OriginPaths.SettingsSyncStore). Pure data + load/save — the merge rules
// live in SettingsSyncEngine.
//
//   Files[fileId][key]               the synced value of every key, and WHEN it
//                                    last changed (the file's write time at the
//                                    harvest that saw the change). Newest wins.
//   Baselines[version][fileId][key]  per instance, the values last written to /
//                                    read from it. A key whose current value
//                                    differs from its baseline is a change the
//                                    player made in THAT version.
//
// fileId is the file's path inside an instance with forward slashes
// ("options.txt", "config/originclient-mods.json"); key is the format's own
// key (an options.txt key, a JSON Pointer, a .properties key, or "" for a
// whole file).
public sealed class SyncStoreData
{
    public int Format { get; set; } = 1;

    public Dictionary<string, Dictionary<string, SyncValue>> Files { get; set; } = new();

    public Dictionary<string, Dictionary<string, Dictionary<string, string>>> Baselines { get; set; } = new();

    public Dictionary<string, SyncValue> FileValues(string fileId)
    {
        if (!Files.TryGetValue(fileId, out var values))
            Files[fileId] = values = new Dictionary<string, SyncValue>(StringComparer.Ordinal);
        return values;
    }

    /// <summary>The instance's baseline for a file, or null when this instance has never been synced for it.</summary>
    public Dictionary<string, string>? BaselineOf(string version, string fileId) =>
        Baselines.TryGetValue(version, out var files) && files.TryGetValue(fileId, out var baseline)
            ? baseline
            : null;

    public void SetBaseline(string version, string fileId, Dictionary<string, string> baseline)
    {
        if (!Baselines.TryGetValue(version, out var files))
            Baselines[version] = files = new Dictionary<string, Dictionary<string, string>>(StringComparer.Ordinal);
        files[fileId] = baseline;
    }
}

public sealed class SyncValue
{
    // The raw value as the file holds it. null = the key was deliberately
    // deleted (only formats with AllowsDeletions ever record that).
    [JsonPropertyName("v")]
    public string? Value { get; set; }

    // When it changed: the source file's last-write time, Unix milliseconds UTC.
    [JsonPropertyName("t")]
    public long ChangedAt { get; set; }

    public SyncValue() { }

    public SyncValue(string? value, long changedAt)
    {
        Value = value;
        ChangedAt = changedAt;
    }
}

public static class SyncStoreFile
{
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = false };

    /// <summary>
    /// The saved store, or null when there is none or it can't be read — the
    /// caller then re-seeds from the instances (newest file wins), which loses
    /// nothing the player set.
    /// </summary>
    public static SyncStoreData? Load(string path)
    {
        try
        {
            if (!File.Exists(path)) return null;
            var store = JsonSerializer.Deserialize<SyncStoreData>(File.ReadAllText(path), Options);
            if (store == null || store.Format != 1) return null;
            store.Files ??= new();
            store.Baselines ??= new();
            return store;
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException or NotSupportedException)
        {
            return null;
        }
    }

    public static void Save(string path, SyncStoreData store) =>
        AtomicFile.WriteAllBytes(path, JsonSerializer.SerializeToUtf8Bytes(store, Options));
}
