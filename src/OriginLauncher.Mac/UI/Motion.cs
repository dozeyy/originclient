using Avalonia;
using Avalonia.Animation;
using Avalonia.Styling;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// The motion switch (twin of src/OriginLauncher.App/UI/Motion.cs). The theme's transitions are Avalonia's own
/// (Theme/Buttons.axaml, Theme/Inputs.axaml, timed by the Motion.* keys in Theme/Spacing.axaml); Off (render-shots)
/// removes every one of them, so a shot never catches a fade halfway. Only opacity, translate, scale and colour are
/// ever animated. Code-driven motion (the art drift) checks <see cref="Off"/> itself.
/// </summary>
public static class Motion
{
    /// <summary>Set before the app starts (render-shots).</summary>
    public static bool Off { get; set; }

    /// <summary>
    /// With motion off, one app-wide style clears Transitions on every control, template parts included. A style beats
    /// every ControlTheme setter (Avalonia's value priority), so nothing in the theme needs to know about it.
    /// </summary>
    internal static void ApplyTo(Application app)
    {
        if (!Off) return;
        var style = new Style(x => x.Is<Visual>());
        style.Setters.Add(new Setter(Animatable.TransitionsProperty, null));
        app.Styles.Add(style);
    }
}
