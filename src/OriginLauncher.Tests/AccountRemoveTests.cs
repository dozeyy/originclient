using OriginLauncher.App.Core.Accounts;
using Xunit;

namespace OriginLauncher.Tests;

// Removing an account in the Accounts drawer (AccountStore.Remove): the row (and the tokens inside it) goes, the
// active account hands over to the most recently used one that can play, and a pending one is never chosen.
public class AccountRemoveTests
{
    private static StoredAccount Row(string id, int daysAgo, bool selected = false, bool pending = false) => new()
    {
        Id = id,
        Gamertag = id,
        LastUsedUtc = new DateTimeOffset(2026, 11, 1, 0, 0, 0, TimeSpan.Zero).AddDays(-daysAgo),
        IsSelected = selected,
        ProtectedRefreshToken = "token-" + id,
        PendingMinecraftSignIn = pending,
    };

    [Fact]
    public void Removing_the_active_account_selects_the_most_recently_used_one()
    {
        var list = new List<StoredAccount> { Row("steve", 0, selected: true), Row("alex", 5), Row("kai", 1) };

        AccountStore.Remove(list, "steve");

        Assert.DoesNotContain(list, a => a.Id == "steve");
        Assert.Equal("kai", AccountStore.GetSelected(list)!.Id);
        Assert.Single(list, a => a.IsSelected);
    }

    [Fact]
    public void Removing_another_account_keeps_the_active_one()
    {
        var list = new List<StoredAccount> { Row("steve", 3, selected: true), Row("alex", 0) };

        AccountStore.Remove(list, "alex");

        Assert.Equal("steve", AccountStore.GetSelected(list)!.Id);
        Assert.Single(list);
    }

    [Fact]
    public void A_pending_account_never_takes_over()
    {
        var list = new List<StoredAccount> { Row("steve", 3, selected: true), Row("pending-1", 0, pending: true) };

        AccountStore.Remove(list, "steve");

        Assert.Null(AccountStore.GetSelected(list));
        Assert.False(list.Single().IsSelected);
    }

    [Fact]
    public void Removing_the_last_account_leaves_an_empty_list()
    {
        var list = new List<StoredAccount> { Row("steve", 0, selected: true) };

        AccountStore.Remove(list, "steve");

        Assert.Empty(list);
    }
}
