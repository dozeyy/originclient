using System.Text.Json.Nodes;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Renderers;
using Xunit;

namespace OriginLauncher.Tests;

// The Iris dependency override a Vulkan game needs in the account's config folder (Core/Renderers/IrisDependencyOverride).
// Real files in a temp game folder; never the player's data.
public sealed class IrisDependencyOverrideTests : IDisposable
{
    private readonly string _gameDir = Path.Combine(Path.GetTempPath(), "origin-irisdeps-" + Guid.NewGuid().ToString("N"));
    private string File_ => InstanceLayout.LoaderDependenciesFile(_gameDir);

    public IrisDependencyOverrideTests() => Directory.CreateDirectory(_gameDir);

    public void Dispose()
    {
        try { Directory.Delete(_gameDir, recursive: true); } catch (IOException) { }
    }

    private void Put(string json)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(File_)!);
        File.WriteAllText(File_, json);
    }

    private JsonObject Read() => JsonNode.Parse(File.ReadAllText(File_))!.AsObject();

    private static bool HasOrigins(JsonObject root) =>
        root["overrides"]?["iris"]?["-depends"]?["sodium"]?.GetValue<string>() == "*";

    [Fact]
    public void The_file_lives_in_the_account_config_folder_under_the_loader_s_name()
    {
        Assert.Equal(Path.Combine(_gameDir, "config", "fabric_loader_dependencies.json"), File_);
        Assert.Equal(IrisDependencyOverride.FileName, Path.GetFileName(File_));
    }

    [Fact]
    public void Ensure_creates_the_file_and_the_config_folder()
    {
        var result = IrisDependencyOverride.Ensure(File_);

        Assert.True(result.InPlace);
        Assert.True(result.Changed);
        Assert.Null(result.Problem);
        var root = Read();
        Assert.Equal(1, root["version"]!.GetValue<int>());
        Assert.True(HasOrigins(root));
        Assert.Single(root["overrides"]!.AsObject());
        Assert.False(File.Exists(File_ + ".origin-tmp"));
    }

    [Fact]
    public void Ensure_twice_writes_once()
    {
        IrisDependencyOverride.Ensure(File_);
        var stamp = new DateTime(2020, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        File.SetLastWriteTimeUtc(File_, stamp);

        var again = IrisDependencyOverride.Ensure(File_);

        Assert.True(again.InPlace);
        Assert.False(again.Changed);
        Assert.Equal(stamp, File.GetLastWriteTimeUtc(File_));
    }

    [Fact]
    public void Ensure_merges_into_the_player_s_file_and_keeps_their_overrides()
    {
        Put("""
            {"version":1,"overrides":{
              "somemod":{"+depends":{"othermod":">=2"}},
              "iris":{"+recommends":{"lithium":"*"},"-depends":{"fabric-api":"*"}}}}
            """);

        var result = IrisDependencyOverride.Ensure(File_);

        Assert.True(result.InPlace);
        Assert.True(result.Changed);
        var root = Read();
        Assert.True(HasOrigins(root));
        Assert.Equal(">=2", root["overrides"]!["somemod"]!["+depends"]!["othermod"]!.GetValue<string>());
        Assert.Equal("*", root["overrides"]!["iris"]!["+recommends"]!["lithium"]!.GetValue<string>());
        Assert.Equal("*", root["overrides"]!["iris"]!["-depends"]!["fabric-api"]!.GetValue<string>());
    }

    [Fact]
    public void Ensure_adds_overrides_to_a_file_that_has_only_a_version()
    {
        Put("""{"version":1}""");

        Assert.True(IrisDependencyOverride.Ensure(File_).InPlace);
        Assert.True(HasOrigins(Read()));
    }

    [Theory]
    [InlineData("{not json")]
    [InlineData("[1,2]")]
    [InlineData("""{"version":2,"overrides":{}}""")]
    [InlineData("""{"overrides":{}}""")]
    [InlineData("""{"version":1,"overrides":[]}""")]
    [InlineData("""{"version":1,"overrides":{"iris":"oops"}}""")]
    [InlineData("""{"version":1,"overrides":{"iris":{"-depends":["sodium"]}}}""")]
    [InlineData("""{"version":1,"version":1,"overrides":{}}""")]
    public void A_file_Origin_cant_read_is_left_alone_and_Vulkan_is_not_in_place(string json)
    {
        Put(json);

        var result = IrisDependencyOverride.Ensure(File_);

        Assert.False(result.InPlace);
        Assert.False(result.Changed);
        Assert.Contains(IrisDependencyOverride.FileName, result.Problem);
        Assert.Equal(json, File.ReadAllText(File_));

        // Normal graphics leaves it alone too, and says why.
        Assert.NotNull(IrisDependencyOverride.Remove(File_));
        Assert.Equal(json, File.ReadAllText(File_));
    }

    [Fact]
    public void Remove_deletes_a_file_that_was_Origin_s_alone()
    {
        IrisDependencyOverride.Ensure(File_);

        Assert.Null(IrisDependencyOverride.Remove(File_));
        Assert.False(File.Exists(File_));
    }

    [Fact]
    public void Remove_takes_out_only_Origin_s_entry_and_keeps_the_rest()
    {
        Put("""
            {"version":1,"overrides":{
              "somemod":{"+depends":{"othermod":">=2"}},
              "iris":{"+recommends":{"lithium":"*"},"-depends":{"fabric-api":"*"}}}}
            """);
        IrisDependencyOverride.Ensure(File_);

        Assert.Null(IrisDependencyOverride.Remove(File_));

        var root = Read();
        Assert.False(HasOrigins(root));
        Assert.Equal(">=2", root["overrides"]!["somemod"]!["+depends"]!["othermod"]!.GetValue<string>());
        Assert.Equal("*", root["overrides"]!["iris"]!["+recommends"]!["lithium"]!.GetValue<string>());
        Assert.Equal("*", root["overrides"]!["iris"]!["-depends"]!["fabric-api"]!.GetValue<string>());
    }

    [Fact]
    public void Remove_drops_the_now_empty_iris_entry_but_keeps_other_mods()
    {
        Put("""{"version":1,"overrides":{"somemod":{"-depends":{"x":"*"}}}}""");
        IrisDependencyOverride.Ensure(File_);

        IrisDependencyOverride.Remove(File_);

        var overrides = Read()["overrides"]!.AsObject();
        Assert.False(overrides.ContainsKey("iris"));
        Assert.True(overrides.ContainsKey("somemod"));
    }

    [Fact]
    public void Remove_with_no_file_or_no_Origin_entry_writes_nothing()
    {
        Assert.Null(IrisDependencyOverride.Remove(File_));
        Assert.False(File.Exists(File_));

        const string players = """{"version":1,"overrides":{"somemod":{"-depends":{"x":"*"}}}}""";
        Put(players);
        Assert.Null(IrisDependencyOverride.Remove(File_));
        Assert.Equal(players, File.ReadAllText(File_));
    }
}
