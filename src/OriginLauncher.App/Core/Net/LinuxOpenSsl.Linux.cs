using System.IO;
using System.Runtime.InteropServices;
using OriginLauncher.App.Core.Launch;

namespace OriginLauncher.App.Core.Net;

/// <summary>
/// OpenSSL for the Linux launcher's HTTPS (update check, Microsoft sign-in, Mojang's files). Self-contained .NET still
/// opens the SYSTEM OpenSSL at the first HTTPS call (libssl.so.3, or 1.1 on older distros), and when there is none its
/// crypto shim prints "No usable version of libssl was found" and aborts the process. Fedora 45 ships OpenSSL 4
/// (libssl.so.4) by default, which .NET 10 can't use, so the AppImage and the tar.gz died there (LINUX-1).
///
/// Those two downloads carry their own OpenSSL 3 in openssl/ next to the executable (tools/linux/package.sh: Ubuntu
/// 22.04's libssl3, pinned by SHA-256). <see cref="Prepare"/> runs first thing in Main: a system OpenSSL .NET accepts
/// always wins; only when there is none it loads the bundled libcrypto.so.3 and libssl.so.3 by full path. glibc then
/// hands those same libraries to .NET's later dlopen("libssl.so.3") (it matches already-loaded libraries by soname), so
/// nothing is put in LD_LIBRARY_PATH and the game, Java and xdg-open never see the bundled copy. The bundled OpenSSL
/// looks for CA certificates where Ubuntu keeps them, so the distro's own bundle is named in SSL_CERT_FILE/SSL_CERT_DIR
/// (set with libc setenv: OpenSSL reads them with getenv, which .NET's managed environment doesn't feed).
/// The .deb and .rpm declare OpenSSL 3 as a dependency and carry no bundled copy.
/// </summary>
public static class LinuxOpenSsl
{
    /// <summary>Folder (next to the executable) holding the bundled libcrypto.so.3 + libssl.so.3.</summary>
    public const string BundledFolder = "openssl";

    // What .NET 10's shim accepts, in its own order (System.Security.Cryptography.Native opensslshim).
    private static readonly string[] SystemSonames = ["libssl.so.3", "libssl.so.1.1"];

    // Where distros keep the CA bundle and the hashed certificate folder, most common first.
    internal static readonly string[] CertFiles =
    [
        "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu, Arch, Gentoo, Void
        "/etc/pki/tls/certs/ca-bundle.crt",   // Fedora, RHEL family
        "/etc/ssl/ca-bundle.pem",             // openSUSE
        "/etc/pki/tls/cacert.pem",
        "/etc/ssl/cert.pem",                  // Alpine, Arch (also)
    ];

    internal static readonly string[] CertDirs = ["/etc/ssl/certs", "/etc/pki/tls/certs"];

    /// <summary>Which OpenSSL this process uses, for the self-test and the log ("system libssl.so.3", "bundled ...", "none").</summary>
    public static string State { get; private set; } = "not checked";

    /// <summary>
    /// Makes sure .NET will find an OpenSSL it accepts. False only when there is neither a system one nor a bundled
    /// copy: the caller then explains instead of letting the first HTTPS call abort the process.
    /// </summary>
    public static bool Prepare()
    {
        if (!OperatingSystem.IsLinux()) return true;
        // A version override (CLR_OPENSSL_VERSION_OVERRIDE) means whoever set it knows which OpenSSL to use.
        if (!string.IsNullOrEmpty(Environment.GetEnvironmentVariable("CLR_OPENSSL_VERSION_OVERRIDE")))
        {
            State = "system (CLR_OPENSSL_VERSION_OVERRIDE)";
            return true;
        }
        foreach (var soname in SystemSonames)
        {
            if (NativeLibrary.TryLoad(soname, out _))
            {
                State = "system " + soname;
                return true;
            }
        }

        var dir = Path.Combine(AppContext.BaseDirectory, BundledFolder);
        var crypto = Path.Combine(dir, "libcrypto.so.3");
        var ssl = Path.Combine(dir, "libssl.so.3");
        // libcrypto first: libssl's own dependency on libcrypto.so.3 is then met by the copy already loaded.
        if (File.Exists(crypto) && File.Exists(ssl) && NativeLibrary.TryLoad(crypto, out _) && NativeLibrary.TryLoad(ssl, out _))
        {
            SetIfUnset("SSL_CERT_FILE", PickFirst(CertFiles, File.Exists));
            SetIfUnset("SSL_CERT_DIR", PickFirst(CertDirs, Directory.Exists));
            State = "bundled OpenSSL 3 (this computer has no OpenSSL 3)";
            return true;
        }
        State = "none";
        return false;
    }

    /// <summary>The sentence shown when no OpenSSL can be found at all (a .deb/.rpm install whose dependency was removed).</summary>
    public static string MissingMessage =>
        "Origin needs OpenSSL 3 to go online, and this computer doesn't have it. Install it, then open Origin again: "
        + LinuxPackages.InstallCommand(LinuxPackages.OpenSsl3, LinuxPackages.ThisComputer);

    /// <summary>The first candidate that exists, or null. Pure apart from the probe, so tests pass their own.</summary>
    internal static string? PickFirst(IEnumerable<string> candidates, Func<string, bool> exists) =>
        candidates.FirstOrDefault(exists);

    private static void SetIfUnset(string name, string? value)
    {
        if (value == null || !string.IsNullOrEmpty(Environment.GetEnvironmentVariable(name))) return;
        setenv(name, value, 0);
    }

    [DllImport("libc", SetLastError = true)]
    private static extern int setenv([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string value, int overwrite);
}
