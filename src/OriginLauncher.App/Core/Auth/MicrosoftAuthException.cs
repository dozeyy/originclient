namespace OriginLauncher.App.Core.Auth;

// Carries which stage of the MSA -> Xbox Live -> XSTS -> Minecraft chain
// failed. Message is ALWAYS a plain sentence a player can read (it is shown
// as is on Home, in the add-account card and in Friends); the raw HTTP status
// and response body go in Detail, which only reaches the error log (ToString).
public sealed class MicrosoftAuthException : Exception
{
    /// <summary>The one line every surface shows when Microsoft/Mojang answered 429.</summary>
    public const string RateLimitedMessage =
        "Microsoft is limiting sign-ins right now. Please wait a few minutes and try again.";

    /// <summary>
    /// A saved account's sign-in came back as a different Minecraft profile
    /// (crossed tokens). Its message already says to add the account again.
    /// </summary>
    public const string AccountMismatchStage = "account_mismatch";

    /// <summary>
    /// Saving the sign-in failed: on a Mac the account key lives in the Keychain, which can be locked,
    /// refuse, or not answer in time (Windows DPAPI does not fail there). Nothing was saved; trying
    /// again after unlocking works. See <see cref="Sealed"/>.
    /// </summary>
    public const string KeychainStage = "keychain";

    public const string KeychainMessage =
        "Origin couldn't save your sign-in safely: your Keychain is locked or didn't answer. Unlock it and try again.";

    /// <summary>
    /// Reading a saved sign-in failed because the key store is locked, its unlock prompt was dismissed, or it didn't
    /// answer (Accounts/KeyStoreLockedException). The account is fine: unlocking and pressing Play again works, so the
    /// player is told exactly that, never "add this account again".
    /// </summary>
    public const string KeyStoreLockedStage = "keystore_locked";

    /// <summary>Play's line for <see cref="KeyStoreLockedStage"/>, in the name each OS gives its store.</summary>
    public static string KeyStoreLockedMessage(bool mac) => mac
        ? "Your Keychain is locked: unlock it and press Play again."
        : "Your keyring is locked: unlock it and press Play again.";

    public static MicrosoftAuthException KeyStoreLocked(Accounts.KeyStoreLockedException e) =>
        new(KeyStoreLockedStage, KeyStoreLockedMessage(OperatingSystem.IsMacOS()), e.Message);

    /// <summary>accounts.json couldn't be written (another program held it past the short waits). Nothing changed.</summary>
    public const string AccountStoreStage = "account_store";

    /// <summary>
    /// Runs one AccountStore.Update for a sign-in, turning a refused write into a sign-in failure with words the
    /// player can act on (every surface already shows MicrosoftAuthException.Message), instead of the catch-all
    /// "Couldn't save this account".
    /// </summary>
    public static void Stored(Action write)
    {
        try { write(); }
        catch (System.IO.IOException e)
        {
            throw new MicrosoftAuthException(AccountStoreStage, Accounts.AccountStore.BusyMessage, e.Message);
        }
        catch (UnauthorizedAccessException e)
        {
            throw new MicrosoftAuthException(AccountStoreStage,
                "Origin couldn't save your accounts: the system refused to let it write to its own data folder. Restart the computer and try again.",
                e.Message);
        }
    }

    /// <summary>
    /// Runs one AccountStore.Protect* call, turning its CryptographicException into a sign-in failure
    /// every surface already shows (Home, the add-account card, Friends). Callers seal BEFORE they
    /// write anything, so a refusal leaves accounts.json exactly as it was.
    /// </summary>
    public static string Sealed(Func<string> protect)
    {
        try { return protect(); }
        catch (System.Security.Cryptography.CryptographicException e)
        {
            throw new MicrosoftAuthException(KeychainStage, KeychainMessage, e.Message);
        }
    }

    public string Stage { get; }

    /// <summary>Raw diagnostics (HTTP status + body). Never shown to the player.</summary>
    public string? Detail { get; }

    /// <summary>
    /// True when the step was refused with HTTP 429 (or Origin is still waiting
    /// out an earlier one). Never retry on the spot: wait at least RetryAfter.
    /// </summary>
    public bool RateLimited { get; }

    /// <summary>How long the server asked us to wait (Retry-After), when it said.</summary>
    public TimeSpan? RetryAfter { get; }

    public MicrosoftAuthException(string stage, string message, string? detail = null) : base(message)
    {
        Stage = stage;
        Detail = detail;
    }

    private MicrosoftAuthException(string stage, string? detail, TimeSpan? retryAfter)
        : base(RateLimitedMessage)
    {
        Stage = stage;
        Detail = detail;
        RateLimited = true;
        RetryAfter = retryAfter;
    }

    public static MicrosoftAuthException Limited(string stage, TimeSpan? retryAfter, string? detail = null) =>
        new(stage, detail, retryAfter);

    public override string ToString() =>
        Detail == null ? base.ToString() : $"{base.ToString()}{Environment.NewLine}Detail ({Stage}): {Detail}";
}
