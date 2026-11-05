// Explicit usings: OriginSetup.Tests links this file with implicit usings off.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace OriginLauncher.App.Core.Install;

// Setup's origin-install.json in the install folder (written by src/OriginSetup/Engine/InstallManifest.cs: the
// files Setup placed, '/'-separated and relative, plus ownsFolder). Uninstall removes only the files it lists when
// the player picked a folder Setup didn't create, so files a self-update added later (a new bundled version's jar,
// new runtime files) were left behind after "Origin was removed" (UPG-7). Right before a Windows swap the launcher
// adds the release's files to that list, the same way an update through Setup adds its own. The name is checked by
// tools/release/check_consistency.py.
//  - No record (an install an older Setup made in its own OriginLauncher folder, a dev folder): nothing is written;
//    uninstall already removes such a folder whole, or never touches it.
//  - ownsFolder true: uninstall removes the whole folder anyway; nothing to add.
//  - Listing a file the swap then fails to copy is harmless: uninstall skips a file that isn't there.
// Never throws: a record that can't be read or written only costs the leftover files it was meant to name.
public static class SetupInstallRecord
{
    public const string FileName = "origin-install.json";

    /// <summary>Adds every file under <paramref name="stageDir"/> (relative to it) to the record in <paramref name="installDir"/>.</summary>
    public static bool AddRelease(string installDir, string stageDir)
    {
        try
        {
            var root = Path.GetFullPath(stageDir).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var files = Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories)
                .Select(f => f[(root.Length + 1)..].Replace('\\', '/'));
            return Add(installDir, files);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return false;
        }
    }

    /// <summary>
    /// Adds <paramref name="relativeFiles"/> to the record in <paramref name="installDir"/> (case-insensitive, no
    /// duplicates, every other field kept). True when the record now lists them; false when there is no record, it is
    /// damaged, Setup owns the whole folder, or the write failed.
    /// </summary>
    public static bool Add(string installDir, IEnumerable<string> relativeFiles)
    {
        var path = Path.Combine(installDir, FileName);
        try
        {
            if (!File.Exists(path)) return false;
            if (JsonNode.Parse(File.ReadAllText(path)) is not JsonObject record) return false;
            if (record["ownsFolder"] is JsonValue owns && owns.TryGetValue<bool>(out var ownsFolder) && ownsFolder) return false;
            if (record["files"] is not JsonArray list) return false;

            var known = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var item in list)
                if (item is JsonValue v && v.TryGetValue<string>(out var s)) known.Add(s.Replace('\\', '/'));
            var added = 0;
            foreach (var file in relativeFiles)
            {
                var rel = file.Replace('\\', '/').Trim();
                // Only plain relative paths: Setup's own reader refuses anything else, so it is never written.
                if (rel.Length == 0 || rel.StartsWith('/') || rel.Contains(':')
                    || rel.Split('/').Any(p => p.Length == 0 || p == "." || p == "..")) continue;
                if (string.Equals(rel, FileName, StringComparison.OrdinalIgnoreCase)) continue;
                if (known.Add(rel)) { list.Add(rel); added++; }
            }
            if (added == 0) return true;

            // Whole file or nothing: written beside, then renamed over (Setup reads it at uninstall).
            var temp = path + ".new";
            File.WriteAllText(temp, record.ToJsonString());
            File.Move(temp, path, overwrite: true);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException
                                   or InvalidOperationException or FormatException)
        {
            return false;
        }
    }
}
