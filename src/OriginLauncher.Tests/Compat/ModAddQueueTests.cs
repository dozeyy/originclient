using OriginLauncher.App.Core.Compat;
using Xunit;

namespace OriginLauncher.Tests.Compat;

// Bug run 6, KC-6: the Mods page's one-import-at-a-time rule. A Replace answered while jars were still copying was
// dropped, and jars dropped during a Replace never went in.
public class ModAddQueueTests
{
    private static ReplaceOffer Offer(string file) => new("/drop/" + file, file, "Sodium", "0.6.13", "old-" + file, "0.5.0");

    [Fact]
    public void A_replace_answered_during_an_add_runs_right_after_it()
    {
        var queue = new ModAddQueue();
        var sodium = Offer("sodium-0.6.13.jar");
        Assert.True(queue.TryStart(new ModAddQueue.Work(["a.jar", "b.jar"], null)));

        Assert.False(queue.TryStart(new ModAddQueue.Work(null, sodium)));

        Assert.Same(sodium, queue.Finish(stillSameVersion: true)!.Replace);
        Assert.False(queue.Busy);
        Assert.True(queue.TryStart(new ModAddQueue.Work(null, sodium)));
        Assert.Null(queue.Finish(stillSameVersion: true));
    }

    [Fact]
    public void Jars_dropped_during_a_replace_go_in_after_it_as_one_batch()
    {
        var queue = new ModAddQueue();
        Assert.True(queue.TryStart(new ModAddQueue.Work(null, Offer("iris.jar"))));

        Assert.False(queue.TryStart(new ModAddQueue.Work(["c.jar"], null)));
        Assert.False(queue.TryStart(new ModAddQueue.Work(["d.jar", "e.jar"], null)));

        Assert.Equal(["c.jar", "d.jar", "e.jar"], queue.Finish(stillSameVersion: true)!.Files);
        Assert.False(queue.HasWaiting);
    }

    [Fact]
    public void Waiting_work_runs_in_the_order_it_was_asked()
    {
        var queue = new ModAddQueue();
        var offer = Offer("x.jar");
        queue.TryStart(new ModAddQueue.Work(["a.jar"], null));
        queue.TryStart(new ModAddQueue.Work(null, offer));
        queue.TryStart(new ModAddQueue.Work(["b.jar"], null));

        Assert.Same(offer, queue.Finish(true)!.Replace);
        queue.TryStart(new ModAddQueue.Work(null, offer));
        Assert.Equal(["b.jar"], queue.Finish(true)!.Files);
    }

    [Fact]
    public void Moving_to_another_version_drops_what_waited_for_the_old_one()
    {
        var queue = new ModAddQueue();
        queue.TryStart(new ModAddQueue.Work(["a.jar"], null));
        queue.TryStart(new ModAddQueue.Work(["b.jar"], null));

        Assert.Null(queue.Finish(stillSameVersion: false));
        Assert.False(queue.HasWaiting);
        Assert.False(queue.Busy);
    }

    // Bug run 11 PROF-8: looking at Home and coming back used to wipe unanswered "Replace?" rows, so those jars were
    // never added and nothing said so. They stay on the same version and profile, and go on any real change.
    [Fact]
    public void Unanswered_replace_rows_survive_a_tab_switch_but_not_a_version_or_profile_change()
    {
        Assert.True(ModAddQueue.KeepsAnswers("1.21.1", null, "1.21.1", null, wasDemo: false));
        Assert.True(ModAddQueue.KeepsAnswers("1.21.1", "p1", "1.21.1", "p1", wasDemo: false));
        Assert.False(ModAddQueue.KeepsAnswers("1.21.1", null, "1.20.1", null, wasDemo: false));
        Assert.False(ModAddQueue.KeepsAnswers("1.21.1", null, "1.21.1", "p1", wasDemo: false));
        Assert.False(ModAddQueue.KeepsAnswers("1.21.1", "p1", "1.21.1", "p2", wasDemo: false));
        Assert.False(ModAddQueue.KeepsAnswers("1.21.1", null, "1.21.1", null, wasDemo: true));
        Assert.False(ModAddQueue.KeepsAnswers(null, null, null, null, wasDemo: false));
    }
}
