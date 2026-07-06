namespace OriginLauncher.App.Core.Auth;

// Carries which stage of the MSA -> Xbox Live -> XSTS -> Minecraft chain
// failed, so the UI can show something more useful than a raw stack trace.
public sealed class MicrosoftAuthException : Exception
{
    public string Stage { get; }

    public MicrosoftAuthException(string stage, string message) : base(message)
    {
        Stage = stage;
    }
}
