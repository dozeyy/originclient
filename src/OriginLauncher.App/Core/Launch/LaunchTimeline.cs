namespace OriginLauncher.App.Core.Launch;

/// <summary>
/// Named points along a launch's preparation (Play -> game process), for CI's
/// launch-time harness (LaunchTimingHarness, `--launch-timing`). The sink is null
/// in every player's launcher, so a mark costs one null check and records nothing.
/// </summary>
public static class LaunchTimeline
{
    /// <summary>Set only by the harness, before the launch it times.</summary>
    public static Action<string>? Sink { get; set; }

    public static void Mark(string name) => Sink?.Invoke(name);
}
