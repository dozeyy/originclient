namespace OriginLauncher.App.Core.Promos;

/// <summary>
/// The Origin web addresses the launcher opens, in one place. Every button that leaves the launcher
/// takes its address from here (never a literal in a click handler), and ExternalLink does the opening.
/// The store address is the one the cosmetics service sends players back to after checkout
/// (services/cosmetics/src/stripe.js STORE_PAGE); OriginLinksTests holds the two equal.
/// </summary>
public static class OriginLinks
{
    /// <summary>The Website button in the top bar.</summary>
    public static readonly Uri Website = new("https://originclient.org/");

    /// <summary>The Origin Store card on Home: capes and cosmetics.</summary>
    public static readonly Uri Store = new("https://originclient.org/store");
}
