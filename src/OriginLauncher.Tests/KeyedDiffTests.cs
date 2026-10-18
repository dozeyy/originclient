using System.Collections.ObjectModel;
using System.Collections.Specialized;
using OriginLauncher.App.Core.Social;
using OriginLauncher.App.UI.Controls;
using Xunit;

namespace OriginLauncher.Tests;

/// <summary>The Friends drawer redraws by row key: an insert or a trimmed chat must not rebuild the rest.</summary>
public class KeyedDiffTests
{
    private sealed record Row(string Key, string Sig = "");

    private static bool Apply(ObservableCollection<Row> target, params Row[] next) =>
        KeyedDiff.Apply(target, next, r => r.Key, r => r.Sig);

    private static List<NotifyCollectionChangedAction> Record(ObservableCollection<Row> target)
    {
        var actions = new List<NotifyCollectionChangedAction>();
        target.CollectionChanged += (_, e) => actions.Add(e.Action);
        return actions;
    }

    [Fact]
    public void NothingChangedTouchesNothing()
    {
        var list = new ObservableCollection<Row> { new("a"), new("b") };
        var actions = Record(list);
        Assert.False(Apply(list, new Row("a"), new Row("b")));
        Assert.Empty(actions);
    }

    [Fact]
    public void AnInsertNearTheTopKeepsEveryOtherRow()
    {
        var a = new Row("a");
        var b = new Row("b");
        var c = new Row("c");
        var list = new ObservableCollection<Row> { a, b, c };
        var actions = Record(list);
        Assert.True(Apply(list, a, new Row("x"), b, c));
        Assert.Equal([NotifyCollectionChangedAction.Add], actions);
        Assert.Same(b, list[2]);
        Assert.Same(c, list[3]);
    }

    [Fact]
    public void ARemovalIsOneRemove()
    {
        var list = new ObservableCollection<Row> { new("a"), new("b"), new("c") };
        var actions = Record(list);
        Assert.True(Apply(list, new Row("a"), new Row("c")));
        Assert.Equal([NotifyCollectionChangedAction.Remove], actions);
        Assert.Equal(["a", "c"], list.Select(r => r.Key));
    }

    [Fact]
    public void ACappedChatTrimsTheHeadAndAppendsTheTail()
    {
        var list = new ObservableCollection<Row>(Enumerable.Range(1, 200).Select(i => new Row($"m{i}")));
        var kept = list[199];
        var actions = Record(list);
        var next = Enumerable.Range(2, 200).Select(i => new Row($"m{i}")).ToArray();
        Assert.True(Apply(list, next));
        Assert.Equal([NotifyCollectionChangedAction.Remove, NotifyCollectionChangedAction.Add], actions);
        Assert.Same(kept, list[198]);
        Assert.Equal("m201", list[^1].Key);
    }

    [Fact]
    public void OnlyTheRowWhoseSignatureChangedIsReplaced()
    {
        var list = new ObservableCollection<Row> { new("a", "1"), new("b", "1"), new("c", "1") };
        var actions = Record(list);
        Assert.True(Apply(list, new Row("a", "1"), new Row("b", "2"), new Row("c", "1")));
        Assert.Equal([NotifyCollectionChangedAction.Replace], actions);
        Assert.Equal("2", list[1].Sig);
    }

    [Fact]
    public void AReorderIsAMoveNotARebuild()
    {
        var a = new Row("a");
        var b = new Row("b");
        var c = new Row("c");
        var list = new ObservableCollection<Row> { a, b, c };
        var actions = Record(list);
        Assert.True(Apply(list, c, a, b));
        Assert.Equal([NotifyCollectionChangedAction.Move], actions);
        Assert.Equal(["c", "a", "b"], list.Select(r => r.Key));
    }

    [Fact]
    public void AnyChangeEndsWithExactlyTheNewList()
    {
        var rng = new Random(7);
        var list = new ObservableCollection<Row>();
        for (var round = 0; round < 300; round++)
        {
            var keys = Enumerable.Range(0, 12).Where(_ => rng.Next(2) == 0).OrderBy(_ => rng.Next()).ToArray();
            var next = keys.Select(k => new Row($"k{k}", rng.Next(3).ToString())).ToArray();
            Apply(list, next);
            Assert.Equal(next.Select(r => r.Key + r.Sig), list.Select(r => r.Key + r.Sig));
        }
    }
}

public class SocialActivityLineTests
{
    private static FriendInfo Friend(bool online, string? client = "game", string? activity = "server",
        string? server = "hypixel.net", string? version = "1.21.1") =>
        new("u", "Steve", online, client, activity, server, version, 0);

    [Fact]
    public void ActivityLineLeavesTheVersionOff()
    {
        Assert.Equal("Playing on hypixel.net", SocialText.ActivityLine(Friend(true)));
        Assert.Equal("Playing singleplayer", SocialText.ActivityLine(Friend(true, activity: "singleplayer", server: null)));
        Assert.Equal("In game", SocialText.ActivityLine(Friend(true, activity: "menu", server: null)));
        Assert.Equal("In the launcher", SocialText.ActivityLine(Friend(true, client: "launcher", activity: "menu")));
        Assert.Equal("Offline", SocialText.ActivityLine(Friend(false)));
    }

    [Fact]
    public void StatusLineIsTheActivityPlusTheVersionWhenThereIsOne()
    {
        var f = Friend(true);
        Assert.Equal(SocialText.ActivityLine(f) + " · 1.21.1", SocialText.StatusLine(f));
        Assert.Equal("In game", SocialText.StatusLine(Friend(true, activity: "menu", server: null, version: null)));
        Assert.Equal("In the launcher", SocialText.StatusLine(Friend(true, client: "launcher", activity: "menu")));
    }
}
