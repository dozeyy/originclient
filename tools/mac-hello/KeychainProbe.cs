using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace MacHello;

// Does a key this app stored survive replacing the app with a re-signed build, with no prompt?
// Two ways of storing, because they answer differently:
//   cli - the app runs /usr/bin/security. The item's access list trusts /usr/bin/security, which
//         never changes, so the app's own signature doesn't matter.
//   api - the app calls Security.framework itself. The access list trusts THIS app's code identity;
//         for an ad-hoc signature that identity is the exact code hash, which changes every build.
// The value is random test data, never a credential.
internal static class KeychainProbe
{
    private const string Account = "probe";
    private const string CliService = "org.originclient.hello.cli";
    private const string ApiService = "org.originclient.hello.api";
    private const string Security = "/System/Library/Frameworks/Security.framework/Security";
    private const int ErrSecInteractionNotAllowed = -25308;

    public static int Run(string mode, string valueFile, bool allowPrompt)
    {
        Console.WriteLine($"KEYCHAIN mode={mode} path={Environment.ProcessPath}");
        // Off: a would-be prompt comes back as -25308 instead of a dialog waiting forever.
        SecKeychainSetUserInteractionAllowed(allowPrompt ? (byte)1 : (byte)0);

        if (mode == "set")
        {
            var value = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
            File.WriteAllText(valueFile, value);
            Console.WriteLine($"KEYCHAIN cli add exit={Cli("add-generic-password", "-a", Account, "-s", CliService, "-w", value, "-U").Exit}");
            Console.WriteLine($"KEYCHAIN api add status={ApiAdd(value)}");
            return 0;
        }

        var expected = File.ReadAllText(valueFile).Trim();
        var cli = Cli("find-generic-password", "-a", Account, "-s", CliService, "-w");
        Console.WriteLine($"KEYCHAIN cli find exit={cli.Exit} match={(cli.Output.Trim() == expected ? "yes" : "no")}");
        var (status, read) = ApiFind();
        var note = status == ErrSecInteractionNotAllowed ? " (errSecInteractionNotAllowed: would prompt)" : "";
        Console.WriteLine($"KEYCHAIN api find status={status}{note} match={(read == expected ? "yes" : "no")}");
        return 0;
    }

    private static (int Exit, string Output) Cli(params string[] args)
    {
        var start = new ProcessStartInfo("/usr/bin/security")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        foreach (var a in args) start.ArgumentList.Add(a);
        using var process = Process.Start(start)!;
        var output = process.StandardOutput.ReadToEnd();
        var error = process.StandardError.ReadToEnd();
        process.WaitForExit();
        if (error.Length > 0) Console.WriteLine("KEYCHAIN cli stderr: " + error.Trim());
        return (process.ExitCode, output);
    }

    private static int ApiAdd(string value)
    {
        var s = Encoding.UTF8.GetBytes(ApiService);
        var a = Encoding.UTF8.GetBytes(Account);
        var v = Encoding.UTF8.GetBytes(value);
        return SecKeychainAddGenericPassword(IntPtr.Zero, (uint)s.Length, s, (uint)a.Length, a, (uint)v.Length, v, IntPtr.Zero);
    }

    private static (int Status, string? Value) ApiFind()
    {
        var s = Encoding.UTF8.GetBytes(ApiService);
        var a = Encoding.UTF8.GetBytes(Account);
        var status = SecKeychainFindGenericPassword(IntPtr.Zero, (uint)s.Length, s, (uint)a.Length, a, out var length, out var data, IntPtr.Zero);
        if (status != 0) return (status, null);
        var value = Marshal.PtrToStringUTF8(data, (int)length);
        SecKeychainItemFreeContent(IntPtr.Zero, data);
        return (status, value);
    }

    [DllImport(Security)] private static extern int SecKeychainSetUserInteractionAllowed(byte allowed);
    [DllImport(Security)] private static extern int SecKeychainAddGenericPassword(IntPtr keychain, uint serviceLength, byte[] service, uint accountLength, byte[] account, uint passwordLength, byte[] password, IntPtr itemRef);
    [DllImport(Security)] private static extern int SecKeychainFindGenericPassword(IntPtr keychainOrArray, uint serviceLength, byte[] service, uint accountLength, byte[] account, out uint passwordLength, out IntPtr passwordData, IntPtr itemRef);
    [DllImport(Security)] private static extern int SecKeychainItemFreeContent(IntPtr attrList, IntPtr data);
}
