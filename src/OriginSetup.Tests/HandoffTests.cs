using OriginSetup.Engine;
using Xunit;

namespace OriginSetup.Tests;

// What the installer writes for the launcher (Engine/Handoff.cs). The launcher
// side of the round trip is OriginLauncher.Tests/SetupHandoffTests.cs.
public class HandoffTests
{
    [Fact]
    public void WritesOnlyTheFieldsThePlayerSet()
    {
        Assert.Null(Handoff.Compose(null, null, null));
        Assert.Equal("{ \"RamMb\": 6144 }", Handoff.Compose(6144, null, null));
        Assert.Equal("{ \"SettingsSyncEnabled\": true }", Handoff.Compose(null, null, true));
        Assert.Equal("{ \"SettingsSyncEnabled\": false }", Handoff.Compose(null, null, false));
    }

    [Fact]
    public void KeepsTheOlderFieldsInTheirOldFormat()
    {
        Assert.Equal("{ \"RamMb\": 4096, \"SelectedVersion\": \"1.21.1\", \"SettingsSyncEnabled\": true }",
            Handoff.Compose(4096, "1.21.1", true));
        Assert.Equal("{ \"RamMb\": 4096, \"SelectedVersion\": \"1.21.1\" }", Handoff.Compose(4096, "1.21.1", null));
    }
}
