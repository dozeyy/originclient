namespace OriginLauncher.App.Core.Auth;

/// <summary>
/// A saved account whose Microsoft sign-in no longer works (refresh refused after ~90 days unused or a password
/// change, or a saved sign-in that can't be read). Signing in with that Microsoft account again is the whole fix: adding
/// an account that is already there refreshes it (AccountSwitcherPanel.CompleteSignIn), so nothing has to be removed
/// first. The same words on Windows, Mac and Linux; Play's refusal also opens the prompt, whose button goes straight to
/// the sign-in card.
/// </summary>
public static class SignInAgain
{
    /// <summary>Play's line when Microsoft refused the saved sign-in.</summary>
    public const string ExpiredLine = "Your Microsoft sign-in expired. Sign in again to play.";

    /// <summary>Play's line when the saved sign-in can't be read any more.</summary>
    public const string UnreadableLine = "Your saved sign-in no longer works. Sign in again to play.";

    /// <summary>Friends' line when its sign-in keeps failing.</summary>
    public const string FriendsLine = "Couldn't sign in to friends. Sign in to this account again from Accounts.";

    public const string Title = "Sign in again";
    public const string Button = "Sign in again";
    public const string Later = "Not now";

    public static string Prompt(string accountName) =>
        $"{accountName}'s Microsoft sign-in expired. Sign in with that Microsoft account again to keep playing - " +
        "worlds, settings and friends stay as they are.";

    /// <summary>
    /// Only signing in again fixes this failure: Microsoft refused the saved refresh token. A 429 (wait) or an outage
    /// (try later, stage "token_refresh_unavailable") is never a reason to sign in again.
    /// </summary>
    public static bool Needed(MicrosoftAuthException ex) =>
        ex.Stage == MicrosoftAuthException.TokenRefreshStage && !ex.RateLimited;
}
