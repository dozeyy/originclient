using Avalonia;
using Avalonia.Controls;

namespace OriginLauncher.Mac.UI;

/// <summary>
/// An Apple capsule (twin of the WPF UI/Capsule.cs): ui:Capsule.On="True" keeps a Border's corner radius at half its
/// shorter side, so a pill, chip, badge, dot or switch track is a true capsule at every size, never an oval
/// (DECISIONS: "No ovals anywhere", 2026-10-25). A round dot is the square case of the same rule.
/// </summary>
public static class Capsule
{
    public static readonly AttachedProperty<bool> OnProperty =
        AvaloniaProperty.RegisterAttached<Border, bool>("On", typeof(Capsule));

    public static bool GetOn(Border b) => b.GetValue(OnProperty);
    public static void SetOn(Border b, bool value) => b.SetValue(OnProperty, value);

    static Capsule()
    {
        OnProperty.Changed.AddClassHandler<Border>((b, e) =>
        {
            b.SizeChanged -= Resize;
            if (e.NewValue is not true) return;
            b.SizeChanged += Resize;
            // A fixed size is known before layout: the first frame is already round.
            if (!double.IsNaN(b.Width) && !double.IsNaN(b.Height)) Apply(b, b.Width, b.Height);
        });
    }

    private static void Resize(object? sender, SizeChangedEventArgs e)
    {
        if (sender is Border b) Apply(b, e.NewSize.Width, e.NewSize.Height);
    }

    private static void Apply(Border b, double w, double h)
    {
        var r = Math.Max(0, Math.Min(w, h) / 2);
        if (Math.Abs(b.CornerRadius.TopLeft - r) > 0.01) b.CornerRadius = new CornerRadius(r);
    }
}
