using OriginLauncher.Desktop.Shell;

namespace OriginLauncher.Desktop.Shots;

/// <summary>
/// One picture of the launcher: a fresh window at Width x Height over freshly seeded made-up data, Setup
/// puts it into the state to show (open a page, a drawer, force a hover), then the frame is saved as Name.png.
/// Seed changes the made-up data before the window opens (signed in, profiles, an update waiting).
/// Scale is the display scale the frame is drawn at (1.5 = 150 %): the PNG is Width x Height times Scale pixels.
/// </summary>
public sealed record Shot(string Name, Action<MainWindow> Setup, double Width = 1180, double Height = 740, Action? Seed = null,
    double Scale = 1);

/// <summary>
/// Marks a static method returning IEnumerable&lt;Shot&gt; as a set of shots. ShotRunner finds every set by
/// reflection, so each screen's shots live next to nothing but themselves (one file per area, no shared list).
/// </summary>
[AttributeUsage(AttributeTargets.Method)]
public sealed class ShotSetAttribute : Attribute;
