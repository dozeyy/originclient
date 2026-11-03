using System.IO;
using System.IO.Compression;

namespace OriginLauncher.App.Core.Runtime;

/// <summary>
/// Eclipse Temurin 8 for 1.8.9-era versions, instead of Mojang's 2015 jre-legacy (8u51).
/// Pinned, never "latest": a new build is a code change with a new URL + hash.
/// Source (2026-10-20): Adoptium API
///   api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&amp;image_type=jre&amp;os=windows&amp;vendor=eclipse
/// and the release's own .sha256.txt asset; both gave the same hash.
/// </summary>
public static class TemurinJava8
{
    public static readonly JavaRuntimePin Pin = new(
        Folder: "temurin-8u504-b01",
        Url: "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u504-b01/OpenJDK8U-jre_x64_windows_hotspot_8u504b01.zip",
        Sha256: "82e2cdc6693737c5998445b31f69668fa0da77c7705121053f6508ac84961123",
        SizeBytes: 40104826);
}

public static partial class JavaRuntimeProvisioner
{
    private const string ArchiveFileName = "java.zip";

    // javaw.exe: the console-less java, so a game launch opens no extra window.
    private static string JavaIn(string runtimeRoot) => Path.Combine(runtimeRoot, "bin", "javaw.exe");

    // .NET's ExtractToDirectory refuses entries that would land outside the folder.
    private static void ExtractArchive(string archive, string destination) =>
        ZipFile.ExtractToDirectory(archive, destination);
}
