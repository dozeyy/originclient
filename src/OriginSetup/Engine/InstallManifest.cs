using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;

namespace OriginSetup.Engine;

// origin-install.json in the install folder: Setup's own record of what it put there, the only
// thing uninstall trusts (UninstallPlan). Written by every install and update through Setup.
//  - files: every file Setup placed, relative to the folder; an update over an install ADDS its
//    files to the list, so a file an older build shipped is still removed on uninstall.
//  - ownsFolder: Setup created the folder itself (it did not exist), so everything in it is
//    Origin's, including files the launcher's own self-update added later (UpdateService copies
//    a whole new release over the folder, and those files are in no Setup list). Only then may
//    uninstall remove the whole folder. Carried over unchanged by later installs.
public sealed class InstallManifest
{
    public const string FileName = "origin-install.json";

    public InstallManifest(bool ownsFolder, IEnumerable<string> files)
    {
        OwnsFolder = ownsFolder;
        Files = files.Select(Normalize).Where(f => f.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    public bool OwnsFolder { get; }
    /// <summary>Relative paths, '/'-separated.</summary>
    public IReadOnlyList<string> Files { get; }

    /// <summary>The manifest in <paramref name="dir"/>, or null when there is none or it is unreadable.</summary>
    public static InstallManifest? Read(string dir)
    {
        try
        {
            var path = Path.Combine(dir, FileName);
            if (!File.Exists(path)) return null;
            using var stream = File.OpenRead(path);
            if (new DataContractJsonSerializer(typeof(Data)).ReadObject(stream) is not Data d || d.Files == null) return null;
            return new InstallManifest(d.OwnsFolder, d.Files.Where(f => f != null));
        }
        catch (Exception ex) when (ex is IOException || ex is UnauthorizedAccessException || ex is SerializationException
                                   || ex is InvalidCastException || ex is ArgumentException)
        {
            return null;
        }
    }

    /// <summary>Writes it into <paramref name="dir"/> (whole file or nothing: written aside, then swapped in).</summary>
    public void Write(string dir)
    {
        var path = Path.Combine(dir, FileName);
        var temp = path + ".new";
        using (var ms = new MemoryStream())
        {
            new DataContractJsonSerializer(typeof(Data)).WriteObject(ms, new Data { OwnsFolder = OwnsFolder, Files = Files.ToList() });
            FileOps.Retry(() => File.WriteAllBytes(temp, ms.ToArray()));
        }
        FileOps.Retry(() =>
        {
            if (File.Exists(path)) File.Delete(path);
            File.Move(temp, path);
        });
    }

    /// <summary>
    /// The manifest after installing <paramref name="placed"/> into <paramref name="dir"/>:
    /// the existing one's files plus these, and its ownership. With no manifest yet, the folder
    /// is Origin's when Setup just created it, or when it is an install an older Setup made in
    /// its own OriginLauncher folder (<see cref="UninstallPlan.IsLegacyOwnFolder"/>).
    /// </summary>
    public static InstallManifest Next(string dir, IEnumerable<string> placed, bool createdFolder)
    {
        var existing = Read(dir);
        bool owns = existing?.OwnsFolder ?? (createdFolder || UninstallPlan.IsLegacyOwnFolder(dir));
        return new InstallManifest(owns, (existing?.Files ?? Array.Empty<string>()).Concat(placed));
    }

    /// <summary>
    /// The full path of <paramref name="relative"/> inside <paramref name="dir"/>, or null when
    /// it would point anywhere else (rooted, "..", empty): a damaged or edited manifest can never
    /// make uninstall delete outside the install folder.
    /// </summary>
    public static string? PathIn(string dir, string relative)
    {
        var rel = Normalize(relative);
        if (rel.Length == 0 || rel.Split('/').Any(part => part.Length == 0 || part == "." || part == "..")) return null;
        try
        {
            var root = Path.GetFullPath(dir).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
            var full = Path.GetFullPath(Path.Combine(root, rel.Replace('/', Path.DirectorySeparatorChar)));
            return full.StartsWith(root, StringComparison.OrdinalIgnoreCase) && full.Length > root.Length ? full : null;
        }
        catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException || ex is PathTooLongException)
        {
            return null;
        }
    }

    // A rooted entry ("C:/x", "/x") is kept as written so PathIn refuses it, never made relative.
    private static string Normalize(string? path) =>
        string.IsNullOrWhiteSpace(path) ? "" : path!.Replace('\\', '/').Trim();

    [DataContract]
    private sealed class Data
    {
        [DataMember(Name = "ownsFolder", Order = 1)] public bool OwnsFolder { get; set; }
        [DataMember(Name = "files", Order = 2)] public List<string>? Files { get; set; }
    }
}
