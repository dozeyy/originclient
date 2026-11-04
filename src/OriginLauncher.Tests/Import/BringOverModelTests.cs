using OriginLauncher.App.Core.Import;
using OriginLauncher.App.Core.Import.Launcher;
using Xunit;

namespace OriginLauncher.Tests.Import;

// Home's one-click bring-over button (Will, 2026-11-04): one launcher = one click transfers it; several = a choice with
// "All of them"; it goes away for good on No thanks or a finished transfer. The state every launcher's Home draws.
public class BringOverModelTests
{
    private static DetectedInstance Setup(SourceKind kind, string dir) =>
        new() { Kind = kind, Name = dir, GameDir = dir, McVersion = "1.21.1" };

    private static readonly DetectedInstance[] OneLauncher = { Setup(SourceKind.Lunar, "a"), Setup(SourceKind.Lunar, "b") };
    private static readonly DetectedInstance[] TwoLaunchers = { Setup(SourceKind.Lunar, "a"), Setup(SourceKind.Prism, "p") };

    [Fact]
    public void One_launcher_one_press_runs_its_transfer_with_no_choice()
    {
        var model = new BringOverModel();
        model.Offer(OneLauncher);

        Assert.Equal(BringOverStage.Offer, model.Stage);
        Assert.Equal("Bring over your Lunar setup", model.Line);
        Assert.Equal(SourceKind.Lunar, model.OneSource);
        Assert.Equal(BringOverPress.RunOne, model.Press());
        Assert.Equal(BringOverStage.Offer, model.Stage); // the window starts the run; the press itself picks nothing

        Assert.True(model.Start(model.OneSource));
        Assert.Equal(BringOverStage.Running, model.Stage);
        Assert.Equal("Checking what can come over...", model.Line);
        Assert.Null(model.Fraction); // no rail until there is a real number
        Assert.Equal(BringOverPress.None, model.Press());
        Assert.False(model.Start(SourceKind.Prism)); // never two at once
    }

    [Fact]
    public void Several_launchers_open_a_choice_and_a_second_press_closes_it()
    {
        var model = new BringOverModel();
        model.Offer(TwoLaunchers);

        Assert.Null(model.OneSource);
        Assert.Equal("Bring over your Minecraft setups", model.Line);
        Assert.Equal(BringOverPress.Choose, model.Press());
        Assert.Equal(BringOverStage.Choosing, model.Stage);
        Assert.Equal(new[] { SourceKind.Lunar, SourceKind.Prism }, model.Sources.Select(s => s.Kind));
        Assert.Equal(BringOverPress.Choose, model.Press());
        Assert.Equal(BringOverStage.Offer, model.Stage);

        model.Press();
        Assert.True(model.Start(null)); // "All of them"
        Assert.Equal("Checking what can come over...", model.Line);
        model.Report(new HomeImportProgress(HomeImportStep.Copying, 0.5));
        Assert.Equal("Bringing over your setups, 50 %", model.Line);
    }

    [Fact]
    public void Progress_never_goes_backwards()
    {
        var model = new BringOverModel();
        model.Offer(OneLauncher);
        model.Start(SourceKind.Lunar);
        model.Report(new HomeImportProgress(HomeImportStep.Copying, 0.6));
        model.Report(new HomeImportProgress(HomeImportStep.Copying, 0.2));
        Assert.Equal(0.6, model.Fraction);
        Assert.Equal("Bringing over your Lunar setup, 60 %", model.Line);
    }

    [Fact]
    public void A_finished_transfer_records_the_answer_and_shows_one_line()
    {
        var model = new BringOverModel();
        model.Offer(OneLauncher);
        model.Start(SourceKind.Lunar);

        var record = model.Finish(new HomeImportOutcome(true, "Your Lunar setup is in Origin.", "Didn't come over: Lunar: b."));

        Assert.True(record);
        Assert.Equal(BringOverStage.Done, model.Stage);
        Assert.Equal("Your Lunar setup is in Origin.", model.Line);
        Assert.Equal("Didn't come over: Lunar: b.", model.Detail);
        Assert.True(model.Dismiss());
        Assert.False(model.Visible);
    }

    [Fact]
    public void A_failed_transfer_keeps_the_offer_open_for_Try_again()
    {
        var model = new BringOverModel();
        model.Offer(TwoLaunchers);
        model.Start(SourceKind.Prism);

        Assert.False(model.Finish(HomeImport.Stopped)); // nothing came over: not answered yet
        Assert.Equal(BringOverStage.Failed, model.Stage);
        Assert.Equal("Stopped. Nothing changed.", model.Line);

        Assert.True(model.Retry(out var again));
        Assert.Equal(SourceKind.Prism, again);
        Assert.Equal(BringOverStage.Running, model.Stage);
    }

    [Fact]
    public void No_thanks_hides_it_for_good_and_a_run_cannot_be_closed()
    {
        var model = new BringOverModel();
        Assert.False(model.Dismiss()); // nothing on screen: nothing to record

        model.Offer(OneLauncher);
        Assert.True(model.Dismiss());
        Assert.Equal(BringOverStage.Hidden, model.Stage);

        model.Offer(OneLauncher);
        model.Start(SourceKind.Lunar);
        Assert.False(model.Dismiss());
        Assert.Equal(BringOverStage.Running, model.Stage);
    }

    [Fact]
    public void A_refused_offer_stays_on_screen_says_why_and_cannot_be_pressed()
    {
        const string why = "Wait for Minecraft to finish starting, then try again.";
        var model = new BringOverModel();
        model.Offer(OneLauncher);
        model.SetRefused(why);
        Assert.True(model.Visible);
        Assert.False(model.CanPress);
        Assert.Equal(why, model.Detail);
        Assert.Equal(BringOverPress.None, model.Press());
        Assert.False(model.Start(SourceKind.Lunar));
        model.SetRefused(null);
        Assert.True(model.CanPress);
        Assert.Null(model.Detail);

        model.Start(SourceKind.Lunar);
        model.SetRefused(why);
        Assert.True(model.Visible);
        Assert.NotEqual(why, model.Detail); // a run never shows the refusal
        model.Finish(new HomeImportOutcome(true, "done"));
        Assert.True(model.Visible);
    }

    [Fact]
    public void Nothing_qualifying_is_no_button_and_opening_the_Import_page_retires_the_offer()
    {
        var model = new BringOverModel();
        model.Offer(Array.Empty<DetectedInstance>());
        Assert.Equal(BringOverStage.Hidden, model.Stage);

        model.Offer(TwoLaunchers);
        model.Press();
        model.HideOffer();
        Assert.Equal(BringOverStage.Hidden, model.Stage);

        // A run already going keeps its place when the page opens elsewhere, and a late scan never replaces it.
        model.Offer(OneLauncher);
        model.Start(SourceKind.Lunar);
        model.HideOffer();
        model.Offer(TwoLaunchers);
        Assert.Equal(BringOverStage.Running, model.Stage);
    }
}
