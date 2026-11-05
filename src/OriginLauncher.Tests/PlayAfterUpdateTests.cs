using OriginLauncher.App.Core.Updates;
using Xunit;

namespace OriginLauncher.Tests;

// Bug run 5, JL-5: Play -> "Update required" -> Update -> the launcher restarts on Home -> press Play again. Now the
// prompt's "Update and play" leaves a note right before the quit, and the next start launches the same game
// (Core/Updates/PlayAfterUpdate). These pin when that note may start a game: once, soon, and only on the new build.
public sealed class PlayAfterUpdateTests : IDisposable
{
    private static readonly Version Old = new(1, 0, 91);
    private static readonly Version New = new(1, 0, 92);
    private static readonly DateTime T0 = new(2026, 11, 5, 12, 0, 0, DateTimeKind.Utc);

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "origin-playafter-" + Guid.NewGuid().ToString("N"));
    private string Marker => Path.Combine(_dir, "updates", "play-after-update.json");

    public void Dispose()
    {
        try { Directory.Delete(_dir, recursive: true); } catch (IOException) { }
    }

    [Fact]
    public void The_updated_launcher_continues_the_refused_play_exactly_once()
    {
        PlayAfterUpdateMarker.Record(Marker, new PlayAfterUpdate("uuid-steve", "1.21.1"), New, T0);

        var play = PlayAfterUpdateMarker.Take(Marker, New, T0.AddSeconds(40));
        Assert.Equal(new PlayAfterUpdate("uuid-steve", "1.21.1"), play);
        Assert.False(File.Exists(Marker));
        Assert.Null(PlayAfterUpdateMarker.Take(Marker, New, T0.AddSeconds(41))); // never twice
    }

    [Fact]
    public void A_join_continues_into_the_same_server()
    {
        PlayAfterUpdateMarker.Record(Marker, new PlayAfterUpdate("offline", "1.20.1", "play.example.net:25565"), New, T0);
        Assert.Equal("play.example.net:25565", PlayAfterUpdateMarker.Take(Marker, New, T0.AddMinutes(1))!.Server);
    }

    [Fact]
    public void A_failed_swap_or_an_old_note_starts_nothing_and_is_removed()
    {
        // The swap failed: the old launcher started again. The player is back on Home with Play as usual.
        PlayAfterUpdateMarker.Record(Marker, new PlayAfterUpdate("uuid-steve", "1.21.1"), New, T0);
        Assert.Null(PlayAfterUpdateMarker.Take(Marker, Old, T0.AddSeconds(30)));
        Assert.False(File.Exists(Marker));

        // A restart the player has long forgotten about (the launcher was closed during the swap and opened tomorrow).
        PlayAfterUpdateMarker.Record(Marker, new PlayAfterUpdate("uuid-steve", "1.21.1"), New, T0);
        Assert.Null(PlayAfterUpdateMarker.Take(Marker, New, T0 + PlayAfterUpdateMarker.MaxAge + TimeSpan.FromSeconds(1)));
        Assert.False(File.Exists(Marker));

        // A newer build than the update (another came in between) still counts as updated.
        PlayAfterUpdateMarker.Record(Marker, new PlayAfterUpdate("uuid-steve", "1.21.1"), New, T0);
        Assert.NotNull(PlayAfterUpdateMarker.Take(Marker, new Version(1, 0, 93), T0.AddMinutes(2)));
    }

    [Fact]
    public void No_note_or_a_damaged_note_never_throws()
    {
        Assert.Null(PlayAfterUpdateMarker.Take(Marker, New, T0));
        Directory.CreateDirectory(Path.GetDirectoryName(Marker)!);
        File.WriteAllText(Marker, "{ not json");
        Assert.Null(PlayAfterUpdateMarker.Take(Marker, New, T0));
        Assert.False(File.Exists(Marker));
    }

    [Fact]
    public void The_prompt_says_play_needs_the_update_and_that_the_game_starts_after_it()
    {
        Assert.Equal("Update and play", UpdateRequiredWords.UpdateButton);
        Assert.Contains("Play needs it", UpdateRequiredWords.PromptText);
        Assert.Contains("starts your game", UpdateRequiredWords.PromptText);
    }
}
