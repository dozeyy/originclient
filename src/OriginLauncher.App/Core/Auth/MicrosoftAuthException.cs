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
