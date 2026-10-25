using OriginLauncher.App.Core.Promos;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The Website button and the Origin Store card open the addresses the project configures.</summary>
public class OriginLinksTests
{
    [Fact]
    public void WebsiteIsTheSiteRoot()
    {
        Assert.Equal("https://originclient.org/", OriginLinks.Website.AbsoluteUri);
    }

    [Fact]
    public void StoreIsTheStorePage()
    {
        Assert.Equal("https://originclient.org/store", OriginLinks.Store.AbsoluteUri);
    }

    [Fact]
    public void BothLinksMayLeaveTheLauncher()
    {
        Assert.True(ExternalLink.IsOpenable(OriginLinks.Website));
        Assert.True(ExternalLink.IsOpenable(OriginLinks.Store));
    }

    [Fact]
    public void StoreMatchesTheAddressCheckoutSendsPlayersBackTo()
    {
        var root = RepoRoot();
        if (root == null) return; // run outside a checkout: nothing to compare against
        var stripe = File.ReadAllText(Path.Combine(root, "services", "cosmetics", "src", "stripe.js"));
        Assert.Contains($"const STORE_PAGE = '{OriginLinks.Store.AbsoluteUri}';", stripe);
        Assert.True(File.Exists(Path.Combine(root, "website", "store.html")), "the page the store link opens");
    }

    [Fact]
    public void ClickHandlersTakeTheirAddressFromOriginLinks()
    {
        var root = RepoRoot();
        if (root == null) return;
        var app = Path.Combine(root, "src", "OriginLauncher.App");
        var main = File.ReadAllText(Path.Combine(app, "MainWindow.xaml.cs"));
        var home = File.ReadAllText(Path.Combine(app, "UI", "Pages", "HomePage.xaml.cs"));
        Assert.Contains("OriginLinks.Website", main);
        Assert.Contains("OriginLinks.Store", home);
        Assert.DoesNotContain("new Uri(\"https://originclient.org", main);
        Assert.DoesNotContain("new Uri(\"https://originclient.org", home);
    }

    private static string? RepoRoot()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
            if (File.Exists(Path.Combine(dir.FullName, "services", "cosmetics", "src", "stripe.js"))) return dir.FullName;
        return null;
    }
}
