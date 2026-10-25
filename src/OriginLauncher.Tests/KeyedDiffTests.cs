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

    private static int Count(List<NotifyCollectionChangedAction> actions, NotifyCollectionChangedAction kind) =>
        actions.Count(a => a == kind);

    [Fact]
    public void RowsWithUnchangedSignaturesKeepTheirInstances()
    {
        var originals = Enumerable.Range(0, 6).Select(i => new Row($"k{i}", "s")).ToList();
        var list = new ObservableCollection<Row>(originals);
        // Same keys and contents in a new order, as brand-new objects: nothing may be replaced.
        var next = new[] { 3, 0, 1, 5, 2, 4 }.Select(i => new Row($"k{i}", "s")).ToArray();
        Apply(list, next);
        Assert.Equal(next.Select(r => r.Key), list.Select(r => r.Key));
        Assert.All(list, row => Assert.Contains(originals, o => ReferenceEquals(o, row)));
    }

    [Fact]
    public void ARowJumpingTenPlacesIsOneMove()
    {
        var rows = Enumerable.Range(0, 12).Select(i => new Row($"k{i}")).ToList();

        var down = new ObservableCollection<Row>(rows);
        var downActions = Record(down);
        Apply(down, rows.Skip(1).Append(rows[0]).ToArray()); // the first row goes to the end
        Assert.Equal([NotifyCollectionChangedAction.Move], downActions);
        Assert.Same(rows[0], down[^1]);

        var up = new ObservableCollection<Row>(rows);
        var upActions = Record(up);
        Apply(up, rows.TakeLast(1).Concat(rows.SkipLast(1)).ToArray()); // the last row goes to the front
        Assert.Equal([NotifyCollectionChangedAction.Move], upActions);
        Assert.Same(rows[^1], up[0]);
    }

    [Fact]
    public void ARotationMovesOnlyTheRowsThatTurnedAround()
    {
        var rows = "abcde".Select(c => new Row(c.ToString())).ToList();
        var list = new ObservableCollection<Row>(rows);
        var actions = Record(list);
        Apply(list, rows[2], rows[3], rows[4], rows[0], rows[1]);
        Assert.Equal("cdeab", string.Concat(list.Select(r => r.Key)));
        Assert.Equal(2, Count(actions, NotifyCollectionChangedAction.Move));
        Assert.Equal(2, actions.Count);
    }

    [Fact]
    public void AMoveAndAContentChangeIsAMoveThenAReplace()
    {
        var a = new Row("a", "1");
        var b = new Row("b", "1");
        var c = new Row("c", "1");
        var list = new ObservableCollection<Row> { a, b, c };
        var actions = Record(list);
        Apply(list, new Row("c", "2"), a, b);
        Assert.Equal([NotifyCollectionChangedAction.Move, NotifyCollectionChangedAction.Replace], actions);
        Assert.Equal("c2", list[0].Key + list[0].Sig);
        Assert.Same(a, list[1]);
        Assert.Same(b, list[2]);
    }

    [Fact]
    public void ADuplicateKeyNeverThrowsAndTheListEndsAsRequested()
    {
        var list = new ObservableCollection<Row> { new("a"), new("a"), new("b") };
        Apply(list, new Row("a"), new Row("b"), new Row("a"));
        Assert.Equal("aba", string.Concat(list.Select(r => r.Key)));

        Apply(list, new Row("b"), new Row("b"), new Row("b"), new Row("a"));
        Assert.Equal("bbba", string.Concat(list.Select(r => r.Key)));

        Apply(list, new Row("a"));
        Assert.Equal("a", string.Concat(list.Select(r => r.Key)));
    }

    [Fact]
    public void ATrimOfSeveralOldRowsPlusSeveralNewOnesCostsOnlyThose()
    {
        var list = new ObservableCollection<Row>(Enumerable.Range(1, 200).Select(i => new Row($"m{i}")));
        var kept = list[10];
        var actions = Record(list);
        Apply(list, Enumerable.Range(4, 200).Select(i => new Row($"m{i}")).ToArray());
        Assert.Equal(3, Count(actions, NotifyCollectionChangedAction.Remove));
        Assert.Equal(3, Count(actions, NotifyCollectionChangedAction.Add));
        Assert.Equal(6, actions.Count);
        Assert.Same(kept, list[10 - 3]);
        Assert.Equal("m203", list[^1].Key);
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
        Assert.Equal(SocialText.ActivityLine(f) + " - 1.21.1", SocialText.StatusLine(f));
        Assert.Equal("In game", SocialText.StatusLine(Friend(true, activity: "menu", server: null, version: null)));
        Assert.Equal("In the launcher", SocialText.StatusLine(Friend(true, client: "launcher", activity: "menu")));
    }
}

public class ToastQueueTests
{
    private static readonly DateTimeOffset T0 = new(2026, 10, 18, 12, 0, 0, TimeSpan.Zero);

    private static SocialToast Toast(SocialToastKind kind, string title = "t") => new(kind, title, "body");

    [Fact]
    public void WhatOverflowsIsCounted()
    {
        var queue = new ToastQueue(capacity: 3);
        for (var i = 0; i < 5; i++) queue.Enqueue(Toast(SocialToastKind.Message, $"m{i}"), T0);
        Assert.Equal(2, queue.Overflow);
        Assert.Equal(5, queue.Count);
    }

    [Fact]
    public void ANoticeIsDroppedLastWhenRoomRunsOut()
    {
        var queue = new ToastQueue(capacity: 2);
        queue.Enqueue(Toast(SocialToastKind.Notice, "why"), T0);
        queue.Enqueue(Toast(SocialToastKind.Message, "m1"), T0);
        queue.Enqueue(Toast(SocialToastKind.Message, "m2"), T0);
        Assert.Equal(1, queue.Overflow);
        Assert.Equal("why", queue.TakeNext(T0)!.Value.Toast.Title);
        Assert.Equal("m2", queue.TakeNext(T0)!.Value.Toast.Title);
    }

    [Fact]
    public void OpeningTheDrawerClearsOnlyWhatTheDrawerShows()
    {
        var queue = new ToastQueue(capacity: 5);
        queue.Enqueue(Toast(SocialToastKind.Message), T0);
        queue.Enqueue(Toast(SocialToastKind.Notice, "why"), T0);
        queue.Enqueue(Toast(SocialToastKind.FriendRequest), T0);
        queue.Enqueue(Toast(SocialToastKind.Warp), T0);
        queue.DropDrawerKinds();
        Assert.Equal(1, queue.Count);
        Assert.Equal("why", queue.TakeNext(T0)!.Value.Toast.Title);
        Assert.Null(queue.TakeNext(T0));
    }

    [Fact]
    public void AToastThatOutwaitedItsLifeIsNeverShownLate()
    {
        var queue = new ToastQueue(capacity: 5);
        queue.Enqueue(Toast(SocialToastKind.Message, "old"), T0);
        queue.Enqueue(Toast(SocialToastKind.Message, "fresh"), T0 + TimeSpan.FromSeconds(6));
        var next = queue.TakeNext(T0 + TimeSpan.FromSeconds(8));
        Assert.Equal("fresh", next!.Value.Toast.Title);
        Assert.Null(queue.TakeNext(T0 + TimeSpan.FromSeconds(8)));
        Assert.Equal(0, queue.Count);
    }

    [Fact]
    public void ALateToastGetsOnlyWhatIsLeftOfItsLife()
    {
        var queue = new ToastQueue(capacity: 5);
        queue.Enqueue(Toast(SocialToastKind.Message), T0);
        queue.Enqueue(Toast(SocialToastKind.Warp), T0);
        Assert.Equal(TimeSpan.FromSeconds(2), queue.TakeNext(T0 + TimeSpan.FromSeconds(3))!.Value.Left);
        Assert.Equal(TimeSpan.FromSeconds(27), queue.TakeNext(T0 + TimeSpan.FromSeconds(3))!.Value.Left);
    }
}
