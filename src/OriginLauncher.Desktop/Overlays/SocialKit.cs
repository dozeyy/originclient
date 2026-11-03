using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core.Skins;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The small pieces the account drawer, the friends drawer and the social toasts share (WPF Theme/Social.xaml):
// player heads with a presence dot, the accent-outline button (accept, party chat), the unread count dot and
// the "..." menu. Built from Kit and the tokens only.
internal static class SocialKit
{
    /// <summary>A player's head: the cached face at once (or the default Steve/Alex face), the real one when it loads.</summary>
    public static Control Head(string? uuid, double size, double radius = Radius.Xs)
    {
        var loaded = PlayerHeads.Loaded(uuid);
        var image = Ui.Pixel(loaded ?? PlayerHeads.Default(uuid), size);
        if (loaded == null && !string.IsNullOrEmpty(uuid)) _ = LoadHeadAsync(uuid, image);
        return new Border
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(radius), ClipToBounds = true,
            Background = Palette.CardHover, Child = image, VerticalAlignment = VerticalAlignment.Center,
        };
    }

    private static async Task LoadHeadAsync(string uuid, Image target)
    {
        try
        {
            var head = await PlayerHeads.Get(uuid);
            if (head != null) Dispatcher.UIThread.Post(() => target.Source = head);
        }
        catch (Exception) { /* the default face stays */ }
    }

    /// <summary>The 32 avatar with its presence dot (online green, offline muted), ringed in the drawer's fill.</summary>
    public static Control Avatar(string uuid, bool? online)
    {
        var head = Head(uuid, Sizes.Avatar);
        if (online == null) return head;
        var box = new Panel { Width = Sizes.Avatar, Height = Sizes.Avatar, VerticalAlignment = VerticalAlignment.Center };
        box.Children.Add(head);
        box.Children.Add(new Border
        {
            Width = Sizes.PresenceDot, Height = Sizes.PresenceDot, CornerRadius = new CornerRadius(Sizes.PresenceDot / 2),
            Background = online.Value ? Palette.Success : Palette.TextMuted, BorderBrush = Palette.Card,
            BorderThickness = new Thickness(2), HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, -3, -3),
        });
        return box;
    }

    /// <summary>A row's name (body, medium) and its caption (label size, dim), both cut with dots when long.</summary>
    public static StackPanel NameBlock(string name, string caption, Control? afterName = null, IBrush? captionBrush = null)
    {
        var nameText = Ui.Ellipsis(Ui.Text(name, TypeScale.Body, FontWeight.Medium, Palette.Text));
        Control top = nameText;
        if (afterName != null)
        {
            var g = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,Auto"), ColumnSpacing = Space.S1 };
            g.Children.Add(nameText.At(0));
            g.Children.Add(afterName.At(1));
            top = g;
        }
        var cap = Ui.Ellipsis(Ui.Text(caption, TypeScale.Label, FontWeight.Normal, captionBrush ?? Palette.TextDim));
        var s = Ui.Column(1, top, cap);
        s.VerticalAlignment = VerticalAlignment.Center;
        return s;
    }

    /// <summary>The unread count: a round accent dot with the number ("9+" past nine).</summary>
    public static Border Unread(int count) => new Border
    {
        Width = Sizes.UnreadDot, Height = Sizes.UnreadDot, CornerRadius = new CornerRadius(Sizes.UnreadDot / 2),
        Background = Palette.Accent, VerticalAlignment = VerticalAlignment.Center,
        Child = Ui.Text(count > 9 ? "9+" : Fmt.N(count), TypeScale.Badge, FontWeight.Bold, Palette.OnAccent)
            .Align(HorizontalAlignment.Center),
    }.Tip("Unread messages");

    /// <summary>The accent-outline button (accept a request, open the party chat): no fill, accent line and ink.</summary>
    public static Pressable Outline(Control content, string name, double height = Sizes.ControlMd, double width = double.NaN)
    {
        var p = new Pressable(Look.Plain, content, name)
        {
            Height = height, Width = width, Padding = new Thickness(double.IsNaN(width) ? 10 : 0, 0),
            CornerRadius = new CornerRadius(Radius.Sm), BorderThickness = new Thickness(1),
            VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Left,
        }.Center();
        p.StateChanged += PaintOutline;
        PaintOutline(p);
        ToolTip.SetTip(p, name);
        return p;
    }

    private static void PaintOutline(Pressable p)
    {
        var enabled = p.IsEffectivelyEnabled;
        var hot = (p.IsHovered || p.ForceHover || p.IsPressed) && enabled;
        p.Background = hot ? Palette.AccentSoft : Palette.GhostRest;
        p.BorderBrush = enabled ? Palette.AccentLine : Palette.Line;
        TextElement.SetForeground(p, enabled ? Palette.Accent : Palette.TextMuted);
    }

    /// <summary>The quiet square button (decline a request): the darkest fill, lifts on hover, dim glyph.</summary>
    public static Pressable Square(string glyph, string name, double size = Sizes.ControlMd)
    {
        var p = new Pressable(Look.Plain, Ui.Icon(glyph, Sizes.IconSm), name)
        {
            Width = size, Height = size, CornerRadius = new CornerRadius(Radius.Sm), BorderThickness = new Thickness(1),
            VerticalAlignment = VerticalAlignment.Center,
        }.Center();
        p.StateChanged += PaintSquare;
        PaintSquare(p);
        ToolTip.SetTip(p, name);
        return p;
    }

    private static void PaintSquare(Pressable p)
    {
        var enabled = p.IsEffectivelyEnabled;
        var hot = (p.IsHovered || p.ForceHover || p.IsPressed) && enabled;
        p.Background = hot ? Palette.CardHover : Palette.BgAlt;
        p.BorderBrush = hot ? Palette.LineStrong : Palette.Line;
        TextElement.SetForeground(p, enabled ? hot ? Palette.Text : Palette.TextDim : Palette.TextMuted);
    }

    /// <summary>A strip (Social.xaml Strip): a notice or the "can't reach friends" line under the header.</summary>
    public static Border Strip(Control child) => new()
    {
        Background = Palette.Panel, BorderBrush = Palette.Line, BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(Radius.Sm), Padding = new Thickness(10, Space.S2),
        Margin = new Thickness(Space.S4, 0, Space.S4, 10), Child = child,
    };

    public sealed record MenuEntry(string Label, string Glyph, Action Run, bool Danger = false);

    /// <summary>The "..." menu: groups of entries split by a line, danger entries in red, run after it closes.</summary>
    public static void ShowMenu(Control target, IEnumerable<IReadOnlyList<MenuEntry>> groups)
    {
        var stack = new StackPanel { MinWidth = 184 };
        Flyout? flyout = null;
        foreach (var group in groups.Where(g => g.Count > 0))
        {
            if (stack.Children.Count > 0) stack.Children.Add(Ui.Divider().Margin(0, Space.S1, 0, Space.S1));
            foreach (var entry in group)
            {
                var ink = entry.Danger ? Palette.DangerText : Palette.Text;
                var row = Ui.Row(10, Ui.Icon(entry.Glyph, Sizes.IconSm, entry.Danger ? Palette.Danger : Palette.TextDim),
                    Ui.Text(entry.Label, TypeScale.Body, FontWeight.Normal, ink));
                var item = new Pressable(Look.Ghost, row, entry.Label)
                {
                    Height = Sizes.ControlMd, Padding = new Thickness(10, 0), CornerRadius = new CornerRadius(Radius.Sm),
                };
                var run = entry.Run;
                item.Click += (_, _) =>
                {
                    flyout?.Hide();
                    Dispatcher.UIThread.Post(run);
                };
                stack.Children.Add(item);
            }
        }
        if (stack.Children.Count == 0) return;
        flyout = new Flyout { Content = stack, Placement = PlacementMode.BottomEdgeAlignedRight };
        flyout.ShowAt(target);
    }

    /// <summary>"3 h ago", as plain as a last-seen time allows.</summary>
    public static string Ago(long atMs)
    {
        var span = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(atMs);
        if (span.TotalMinutes < 1) return "just now";
        if (span.TotalMinutes < 60) return $"{(int)span.TotalMinutes} min ago";
        if (span.TotalHours < 24) return $"{(int)span.TotalHours} h ago";
        return $"{(int)span.TotalDays} d ago";
    }

    /// <summary>Puts a panel's children in this order, reusing the controls already there (no flicker, hover kept).</summary>
    public static void Sync(Panel panel, IReadOnlyList<Control> wanted)
    {
        var children = panel.Children;
        for (var i = 0; i < wanted.Count; i++)
        {
            var w = wanted[i];
            if (i < children.Count && ReferenceEquals(children[i], w)) continue;
            var at = children.IndexOf(w);
            if (at >= 0) children.Move(at, i);
            else children.Insert(i, w);
        }
        while (children.Count > wanted.Count) children.RemoveAt(children.Count - 1);
    }
}

/// <summary>A keyed cache of built rows: a row is rebuilt only when its signature changes.</summary>
internal sealed class RowCache
{
    private Dictionary<string, (string Sig, Control Control)> _rows = new(StringComparer.Ordinal);

    public sealed record Spec(string Key, string Sig, Func<Control> Build);

    public IReadOnlyList<Control> Resolve(IEnumerable<Spec> specs)
    {
        var next = new Dictionary<string, (string, Control)>(StringComparer.Ordinal);
        var list = new List<Control>();
        foreach (var spec in specs)
        {
            var control = _rows.TryGetValue(spec.Key, out var old) && old.Sig == spec.Sig ? old.Control : spec.Build();
            next[spec.Key] = (spec.Sig, control);
            list.Add(control);
        }
        _rows = next;
        return list;
    }

    public void Clear() => _rows.Clear();
}
