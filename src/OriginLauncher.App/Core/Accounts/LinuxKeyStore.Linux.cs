using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace OriginLauncher.App.Core.Accounts;

/// <summary>
/// Linux only: where the account-encryption key (AccountStore.Linux.cs) lives. Two homes, tried in
/// this order:
///  1. The desktop's Secret Service keyring (GNOME Keyring, KWallet, KeePassXC...), through
///     libsecret's own <c>secret-tool</c> (package libsecret-tools / libsecret). The tool, not a
///     D-Bus binding: no native library to load or marshal, and it is simply absent on machines
///     without a keyring. The key travels through its stdin, never its command line (argv is
///     visible to every process).
///  2. When there is no keyring (no secret-tool, no session bus, a keyring that errors or does not
///     answer): a random 32-byte secret in &lt;data&gt;/accounts/account.key, mode 0600, and the real
///     key is HKDF-SHA256(secret, salt = this machine's /etc/machine-id). Copying the data folder to
///     another machine (or restoring it on a reinstall with a new machine-id) gives a different key,
///     so the tokens stay on this device; another local user can't read the 0600 file.
/// A keyring that exists but errors is never overwritten (that would lock the player out of every
/// account it sealed): the new blob simply uses the key file. Every failure is a
/// CryptographicException, which the account code treats as "sign in again".
/// </summary>
internal static class LinuxKeyStore
{
    internal const byte KeyringKind = 1;
    internal const byte FileKind = 2;

    private const int KeySize = 32;
    private static readonly byte[] HkdfInfo = Encoding.ASCII.GetBytes("Origin Launcher account key v1");
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(15);

    // The self-test points these at throwaway values; the launcher always uses the defaults.
    internal static string Service { get; set; } = "org.originclient.launcher";
    internal static string Account { get; set; } = "account-key";
    internal static string[] MachineIdFiles { get; set; } = ["/etc/machine-id", "/var/lib/dbus/machine-id"];

    /// <summary>Self-test only: act as a machine with no keyring.</summary>
    internal static bool KeyringDisabled { get; set; }

    private static string? _keyFile;

    /// <summary>The fallback key file. Resolved on first use, so a scratch data root set before that is honoured.</summary>
    internal static string KeyFilePath
    {
        get => _keyFile ?? Path.Combine(OriginPaths.Accounts, "account.key");
        set => _keyFile = value;
    }

    private static readonly object Gate = new();
    private static byte[]? _keyringKey, _fileKey; // one read per run; the process already holds the tokens it seals
    private static bool? _keyringUsable; // decided by the first keyring call of the run

    private enum Lookup { Found, NotFound, Unavailable }

    /// <summary>The key a new blob is sealed with, and which kind it is. Creates one only where none exists.</summary>
    internal static (byte Kind, byte[] Key) GetOrCreateSealingKey()
    {
        lock (Gate)
        {
            if (KeyringReachable() && GetOrCreateKeyringKey() is { } fromKeyring) return (KeyringKind, fromKeyring);
            return (FileKind, GetOrCreateFileKey());
        }
    }

    /// <summary>The key of one kind, or null when there is none. Never creates one.</summary>
    internal static byte[]? TryReadKey(byte kind)
    {
        lock (Gate)
        {
            if (kind == FileKind) return TryReadFileKey();
            if (!KeyringReachable()) return null;
            var (result, key) = KeyringLookup();
            if (result == Lookup.Unavailable) throw new CryptographicException("The keyring did not answer.");
            return key;
        }
    }

    /// <summary>Self-test only: forget the in-memory copies so the next read goes to the keyring / file again.</summary>
    internal static void ForgetCached()
    {
        lock (Gate)
        {
            _keyringKey = null;
            _fileKey = null;
            _keyringUsable = null;
        }
    }

    /// <summary>Self-test only: true when secret-tool exists and the keyring answers right now.</summary>
    internal static bool KeyringAvailableForTests()
    {
        lock (Gate) return KeyringReachable() && KeyringLookup().Result != Lookup.Unavailable;
    }

    /// <summary>Self-test only: deletes the keyring item and forgets the cached copy.</summary>
    internal static void DeleteKeyringItemForTests()
    {
        lock (Gate)
        {
            _keyringKey = null;
            Run(["clear", "service", Service, "account", Account], stdin: null);
        }
    }

    // ---- keyring ---------------------------------------------------------------------------

    // secret-tool needs a session bus; without one it can try to autolaunch D-Bus through X11 and
    // stall, so a machine with no bus is "no keyring" without spawning anything.
    private static bool KeyringReachable()
    {
        if (KeyringDisabled || _keyringUsable == false) return false;
        if (SecretTool == null) return false;
        var runtimeDir = Environment.GetEnvironmentVariable("XDG_RUNTIME_DIR");
        return !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DBUS_SESSION_BUS_ADDRESS"))
               || (!string.IsNullOrEmpty(runtimeDir) && File.Exists(Path.Combine(runtimeDir, "bus")));
    }

    private static readonly Lazy<string?> SecretToolPath = new(() =>
    {
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "/usr/bin:/bin").Split(':', StringSplitOptions.RemoveEmptyEntries))
        {
            var candidate = Path.Combine(dir, "secret-tool");
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    });

    private static string? SecretTool => SecretToolPath.Value;

    // Null when the keyring errors: the caller then seals with the key file instead.
    private static byte[]? GetOrCreateKeyringKey()
    {
        var (result, existing) = KeyringLookup();
        if (result == Lookup.Found) return existing;
        if (result == Lookup.Unavailable) return null;

        var key = RandomNumberGenerator.GetBytes(KeySize);
        try
        {
            // `store` replaces an item with the same attributes (one that held no usable key).
            var (code, _, _) = Run(["store", "--label=Origin Launcher account key", "service", Service, "account", Account],
                stdin: Convert.ToBase64String(key));
            _keyringKey = null;
            var (after, stored) = code == 0 ? KeyringLookup() : (Lookup.Unavailable, null);
            if (after != Lookup.Found || stored == null || !CryptographicOperations.FixedTimeEquals(stored, key))
            {
                // The keyring refused or lost it: treat the keyring as absent for this run.
                if (stored != null) CryptographicOperations.ZeroMemory(stored);
                _keyringUsable = false;
                CryptographicOperations.ZeroMemory(key);
                return null;
            }
            CryptographicOperations.ZeroMemory(stored);
            return key;
        }
        catch (CryptographicException)
        {
            _keyringUsable = false;
            CryptographicOperations.ZeroMemory(key);
            return null;
        }
    }

    // secret-tool lookup: exit 0 + the secret on stdout = found; exit 1 with nothing on stderr = no
    // such item; anything else (no bus, locked keyring nobody unlocked, a crash) = unavailable.
    private static (Lookup Result, byte[]? Key) KeyringLookup()
    {
        if (_keyringKey != null) return (Lookup.Found, (byte[])_keyringKey.Clone());
        int code;
        string output, error;
        try
        {
            (code, output, error) = Run(["lookup", "service", Service, "account", Account], stdin: null);
        }
        catch (CryptographicException)
        {
            _keyringUsable = false;
            return (Lookup.Unavailable, null);
        }
        if (code != 0)
        {
            var notFound = output.Length == 0 && error.Trim().Length == 0;
            _keyringUsable = notFound;
            return (notFound ? Lookup.NotFound : Lookup.Unavailable, null);
        }
        _keyringUsable = true;
        byte[] key;
        try { key = Convert.FromBase64String(output.Trim()); }
        catch (FormatException) { return (Lookup.NotFound, null); } // not a key Origin wrote: nothing was sealed with it
        if (key.Length != KeySize) return (Lookup.NotFound, null);
        _keyringKey = key;
        return (Lookup.Found, (byte[])key.Clone());
    }

    private static (int Code, string Stdout, string Stderr) Run(string[] args, string? stdin)
    {
        var tool = SecretTool ?? throw new CryptographicException("secret-tool is not installed.");
        var psi = new ProcessStartInfo(tool)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        try
        {
            using var p = Process.Start(psi) ?? throw new CryptographicException("Could not start secret-tool.");
            var stdout = p.StandardOutput.ReadToEndAsync();
            var stderr = p.StandardError.ReadToEndAsync();
            if (stdin != null) p.StandardInput.Write(stdin);
            p.StandardInput.Close();
            if (!p.WaitForExit(Timeout))
            {
                // A keyring unlock dialog nobody answers: give up rather than hang a sign-in.
                try { p.Kill(); } catch (InvalidOperationException) { }
                throw new CryptographicException("The keyring did not answer.");
            }
            return (p.ExitCode, stdout.Result, stderr.Result);
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            throw new CryptographicException("secret-tool failed: " + e.Message);
        }
    }

    // ---- key file ----------------------------------------------------------------------------

    private const UnixFileMode OwnerOnly = UnixFileMode.UserRead | UnixFileMode.UserWrite;

    private static byte[]? TryReadFileKey()
    {
        if (_fileKey != null) return (byte[])_fileKey.Clone();
        var secret = ReadSecretFile();
        if (secret == null) return null;
        try
        {
            _fileKey = HKDF.DeriveKey(HashAlgorithmName.SHA256, secret, KeySize, MachineId(), HkdfInfo);
            return (byte[])_fileKey.Clone();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(secret);
        }
    }

    // The 32 secret bytes, or null when the file is missing or isn't one Origin wrote.
    private static byte[]? ReadSecretFile()
    {
        var path = KeyFilePath;
        try
        {
            if (!File.Exists(path)) return null;
            // Someone loosened it (a backup tool, a chmod -R): close it again before using it.
            if ((File.GetUnixFileMode(path) & ~OwnerOnly) != 0) File.SetUnixFileMode(path, OwnerOnly);
            var bytes = File.ReadAllBytes(path);
            if (bytes.Length == KeySize) return bytes;
            CryptographicOperations.ZeroMemory(bytes);
            return null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new CryptographicException("The account key file can't be read: " + e.Message);
        }
    }

    private static byte[] GetOrCreateFileKey()
    {
        if (TryReadFileKey() is { } existing) return existing;
        MachineId(); // no machine id = no device binding: refuse before writing a key that would not be bound
        var path = KeyFilePath;
        var replace = File.Exists(path); // present but not a key Origin wrote: nothing was sealed with it
        var secret = RandomNumberGenerator.GetBytes(KeySize);
        var temp = path + "." + Environment.ProcessId + ".tmp";
        try
        {
            var folder = Path.GetDirectoryName(path)!;
            Directory.CreateDirectory(folder, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            // Created 0600 from the first byte (UnixCreateMode), then renamed in, so no reader ever sees half a key.
            using (var file = new FileStream(temp, new FileStreamOptions
                   {
                       Mode = FileMode.CreateNew, Access = FileAccess.Write, Share = FileShare.None, UnixCreateMode = OwnerOnly,
                   }))
            {
                file.Write(secret);
                file.Flush(flushToDisk: true);
            }
            try
            {
                File.Move(temp, path, overwrite: replace);
            }
            catch (IOException) when (!replace && File.Exists(path))
            {
                // Another Origin process wrote one first: use theirs.
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new CryptographicException("The account key file can't be written: " + e.Message);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(secret);
            try { File.Delete(temp); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
        _fileKey = null;
        return TryReadFileKey() ?? throw new CryptographicException("The account key file did not keep the key.");
    }

    // systemd's /etc/machine-id (dbus's copy on older systems): fixed for the life of the install.
    private static byte[] MachineId()
    {
        foreach (var file in MachineIdFiles)
        {
            try
            {
                if (!File.Exists(file)) continue;
                var id = File.ReadAllText(file).Trim();
                if (id.Length >= 16) return Encoding.ASCII.GetBytes(id);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                // try the next one
            }
        }
        throw new CryptographicException("This machine has no machine-id to bind the account key to.");
    }
}
