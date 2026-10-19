using System.IO;
using System.Text.RegularExpressions;

namespace OriginLauncher.App.Core.Import;

// The one rule that keeps an import from ever touching a secret: a file whose
// name says "account / token / credential / auth / session / secret / password",
// or that is one of the launchers' known credential stores, is never opened and
// never copied. It is listed to the player as "skipped for safety" BY NAME ONLY
// (never a path to a value, never a value).
//
// Every source read in Core/Import goes through SourceFs, which asks this class
// first — so a new detector cannot forget it. The name pattern is deliberately
// broad: skipping a harmless file costs the player one manual copy (the summary
// names it), reading a token file costs far more. (It also skips e.g. a mod jar
// called "authme-*.jar"; the summary tells the player so they can copy it.)
public sealed class CredentialGuard
{
    public static CredentialGuard Default { get; } = new();

    // Known credential stores (all file-name based):
    //   official launcher: launcher_accounts*.json, launcher_msa_credentials*.bin,
    //     launcher_entitlements*, launcher_product_state*, launcher_gamer_pics*
    //   Prism: accounts.json   Modrinth app: app.db   Essential: essential/*accounts*
    private static readonly string[] DeniedPrefixes =
    {
        "launcher_accounts", "launcher_msa_credentials", "launcher_entitlements",
        "launcher_product_state", "launcher_gamer_pics",
    };

    private static readonly Regex DeniedName = new(
        "(account|token|credential|auth|session|secret|password)",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    /// <summary>True when a file with this name (or path — only the file name counts) must never be opened.</summary>
    public bool IsDenied(string pathOrName)
    {
        var name = Path.GetFileName(pathOrName.TrimEnd('/', '\\'));
        if (name.Length == 0) return false;
        foreach (var prefix in DeniedPrefixes)
            if (name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return true;
        if (name.Equals("app.db", StringComparison.OrdinalIgnoreCase)) return true;
        return DeniedName.IsMatch(name);
    }

    /// <summary>
    /// Lock files are never worth copying and are not credentials, so they are
    /// dropped silently rather than reported as "skipped for safety"
    /// (every world holds a session.lock, which the name pattern would catch).
    /// </summary>
    public static bool IsHarmlessNoise(string name) =>
        name.Equals("session.lock", StringComparison.OrdinalIgnoreCase)
        || name.EndsWith(".lock", StringComparison.OrdinalIgnoreCase);
}
