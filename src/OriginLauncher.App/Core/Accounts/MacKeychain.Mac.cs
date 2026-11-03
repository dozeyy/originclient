using System.Diagnostics;
using System.Security.Cryptography;

namespace OriginLauncher.App.Core.Accounts;

/// <summary>
/// Mac only: the account-encryption key (AccountStore.Mac.cs) as a generic password in the
/// user's default (login) Keychain, through Apple's own /usr/bin/security tool. Why the tool and
/// never SecItemAdd/SecItemCopyMatching: an item the tool creates trusts the tool, not the app, so
/// it stays readable with no prompt after a self-update swaps in a newly signed .app. An item the
/// app saved itself through SecItemAdd was NOT readable after that swap (-25293 plus a password
/// dialog; proven on CI, claude/mac-hello run 41571157095), which would sign every player out on
/// every update. The tool also means no CoreFoundation marshalling to get wrong.
/// The key never touches disk outside the Keychain and never appears on a command line: it is
/// written through <c>security -i</c>'s stdin (argv is visible to every process via ps).
/// Every failure is a CryptographicException, which the account code treats as "sign in again".
/// </summary>
internal static class MacKeychain
{
    private const string Tool = "/usr/bin/security";
    private const int KeySize = 32;
    private const int NotFound = 44; // errSecItemNotFound, security's exit code for "no such item"
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(15);

    // The self-test points these at a throwaway item; the launcher always uses the defaults.
    internal static string Service { get; set; } = "org.originclient.launcher";
    internal static string Account { get; set; } = "account-key";

    private static readonly object Gate = new();
    private static byte[]? _cached; // one Keychain read per run; the process already holds the tokens it seals

    /// <summary>The key, or null when the Keychain has none (or one that isn't a 32-byte key). Throws when the Keychain can't be read.</summary>
    internal static byte[]? TryReadKey()
    {
        lock (Gate)
        {
            if (_cached != null) return (byte[])_cached.Clone();
            var (code, output, _) = Run(["find-generic-password", "-s", Service, "-a", Account, "-w"], stdin: null);
            if (code == NotFound) return null;
            if (code != 0) throw new CryptographicException($"Keychain read failed (security exit {code}).");
            byte[] key;
            try { key = Convert.FromBase64String(output.Trim()); }
            catch (FormatException) { return null; }
            if (key.Length != KeySize) return null;
            _cached = key;
            return (byte[])key.Clone();
        }
    }

    /// <summary>
    /// The key, creating one only when the Keychain has none. A Keychain that exists but can't be
    /// read (locked, prompt denied) throws instead: overwriting the item there would lock the player
    /// out of every account they already saved.
    /// </summary>
    internal static byte[] GetOrCreateKey()
    {
        lock (Gate)
        {
            if (TryReadKey() is { } existing) return existing;
            var key = RandomNumberGenerator.GetBytes(KeySize);
            var b64 = Convert.ToBase64String(key);
            try
            {
                // -U replaces an item that held no usable key (wrong length / not base64). Base64 has no
                // spaces or quotes, so it is one token for security's own command parser.
                Run(["-i"], stdin: $"add-generic-password -U -s {Quote(Service)} -a {Quote(Account)} -l {Quote("Origin Launcher account key")} -w {b64}\n");
                _cached = null;
                // security -i exits 0 even when the command inside failed: read it back to know.
                var stored = TryReadKey();
                if (stored == null || !CryptographicOperations.FixedTimeEquals(stored, key))
                    throw new CryptographicException("Keychain did not keep the account key.");
                CryptographicOperations.ZeroMemory(stored);
                return key;
            }
            catch
            {
                CryptographicOperations.ZeroMemory(key);
                throw;
            }
        }
    }

    /// <summary>Self-test only: deletes the item and forgets the cached copy.</summary>
    internal static void DeleteForTests()
    {
        lock (Gate)
        {
            _cached = null;
            Run(["delete-generic-password", "-s", Service, "-a", Account], stdin: null);
        }
    }

    /// <summary>Self-test only: forget the in-memory copy so the next read goes to the Keychain.</summary>
    internal static void ForgetCached()
    {
        lock (Gate) _cached = null;
    }

    // security -i's parser takes double-quoted tokens; the names above are fixed ASCII without quotes.
    private static string Quote(string value) => "\"" + value.Replace("\"", "") + "\"";

    private static (int Code, string Stdout, string Stderr) Run(string[] args, string? stdin)
    {
        var psi = new ProcessStartInfo(Tool)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        try
        {
            using var p = Process.Start(psi) ?? throw new CryptographicException("Could not start the Keychain tool.");
            var stdout = p.StandardOutput.ReadToEndAsync();
            var stderr = p.StandardError.ReadToEndAsync();
            if (stdin != null) p.StandardInput.Write(stdin);
            p.StandardInput.Close();
            if (!p.WaitForExit(Timeout))
            {
                // A Keychain unlock dialog nobody answers: give up rather than hang a sign-in.
                try { p.Kill(); } catch (InvalidOperationException) { }
                throw new CryptographicException("The Keychain did not answer.");
            }
            return (p.ExitCode, stdout.Result, stderr.Result);
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
        {
            throw new CryptographicException("The Keychain tool failed: " + e.Message);
        }
    }
}
