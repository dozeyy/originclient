using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Kit;

/// <summary>One menu item: the value it stands for, its label, optional right-aligned meta and a leading dot.</summary>
public sealed record DropItem(string Value, string Label, string? Meta = null, IBrush? Dot = null);

// The dropdown (11.2). Trigger: secondary anatomy, value in bodyStrong, chevron-down 12 in textDim, 8 before
// the chevron, width = content (or fixed by the caller). Open: fill surfaceHover, edge accentLine, chevron
// turned 180. Menu: surfaceRaised, 1 px lineStrong, radius md, shadow.overlay, 4 inner padding, opens 4 below
// (flips above when there is no room), items md 32 radius sm pad X 10, hover rgba(255,255,255,.06),
// selected = check 14 in accent on the right + label bodyStrong. Keys: Up/Down move, Enter selects, Esc closes.
// Selecting raises Selected; setting Value from code never does.
public sealed class Dropdown : Panel
{
    private readonly Pressable _trigger;
    private readonly TextBlock _value;
    private readonly IconView _chevron;
    private readonly Popup _popup;
    private readonly StackPanel _menu = new() { Spacing = 0 };
    private readonly Control? _leading;
    private IReadOnlyList<DropItem> _items = [];
    private string? _selected;
    private int _hot = -1;

    public event Action<string>? Selected;

    public Dropdown(double height = Sizes.ControlLg, Control? leading = null, string placeholder = "")
    {
        _leading = leading;
        _value = Ui.Text(placeholder, height < Sizes.ControlMd ? TypeScale.ControlSm : TypeScale.Body, FontWeight.SemiBold);
        _value.TextTrimming = TextTrimming.CharacterEllipsis;
        _chevron = Ui.Icon(Icons.ChevronDown, 12, Palette.TextDim);
        _chevron.RenderTransformOrigin = RelativePoint.Center;
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 8 };
        if (leading != null) grid.Children.Add(leading.At(0));
        grid.Children.Add(_value.At(1));
        grid.Children.Add(_chevron.At(2));
        _trigger = new Pressable(Look.Secondary, grid)
        {
            Height = height,
            Padding = new Thickness(height >= Sizes.ControlLg ? 12 : 10, 0),
            CornerRadius = new CornerRadius(height >= Sizes.ControlLg ? Radius.Md : Radius.Sm),
        };
        _trigger.Click += (_, _) => SetOpen(!IsOpen);
        _trigger.StateChanged += _ => PaintOpen();
        _trigger.KeyDown += OnTriggerKey;

        _popup = new Popup
        {
            PlacementTarget = _trigger,
            Placement = PlacementMode.BottomEdgeAlignedLeft,
            VerticalOffset = 4,
            IsLightDismissEnabled = true,
            Child = new Border
            {
                Background = Palette.Card,
                BorderBrush = Palette.LineStrong,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(Radius.Md),
                Padding = new Thickness(4),
                BoxShadow = Palette.OverlayShadow,
                Margin = new Thickness(0, 0, 0, 0),
                MaxWidth = 320,
                Child = new ScrollViewer { Content = _menu, MaxHeight = 360 },
            },
        };
        _popup.Closed += (_, _) => PaintOpen();
        _popup.KeyDown += OnMenuKey;
        Children.Add(_trigger);
        Children.Add(_popup);
        HorizontalAlignment = HorizontalAlignment.Left;
    }

    public Pressable Trigger => _trigger;
    public bool IsOpen => _popup.IsOpen;
    public string? Value => _selected;

    public IReadOnlyList<DropItem> Items
    {
        get => _items;
        set { _items = value; Rebuild(); ShowValue(); }
    }

    public void SetValue(string? value)
    {
        _selected = value;
        ShowValue();
        Rebuild();
    }

    /// <summary>Text shown when no item is selected.</summary>
    public string Placeholder { get; set; } = "";

    public void SetOpen(bool open)
    {
        if (open && _items.Count == 0) return;
        if (open)
        {
            _hot = Math.Max(0, IndexOf(_selected));
            Rebuild();
            _popup.MinWidth = _trigger.Bounds.Width;
        }
        _popup.IsOpen = open;
        PaintOpen();
        if (!open) _trigger.Focus();
    }

    private int IndexOf(string? v)
    {
        for (var i = 0; i < _items.Count; i++) if (_items[i].Value == v) return i;
        return -1;
    }

    private void ShowValue()
    {
        var i = IndexOf(_selected);
        _value.Text = i >= 0 ? _items[i].Label : Placeholder;
        if (i >= 0) _value.ClearValue(TextBlock.ForegroundProperty);
        else _value.Foreground = Palette.TextMuted;
    }

    private void PaintOpen()
    {
        var open = _popup.IsOpen;
        _chevron.RenderTransform = TransformOperations.Parse(open ? "rotate(180deg)" : "rotate(0deg)");
        if (open)
        {
            _trigger.Background = Palette.CardHover;
            _trigger.BorderBrush = Palette.AccentLine;
        }
    }

    private void Rebuild()
    {
        _menu.Children.Clear();
        for (var i = 0; i < _items.Count; i++)
        {
            var item = _items[i];
            var index = i;
            var selected = item.Value == _selected;
            var label = Ui.Text(item.Label, TypeScale.Body, selected ? FontWeight.SemiBold : FontWeight.Normal, Palette.Text);
            var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto,Auto"), ColumnSpacing = 8 };
            Control left = item.Dot == null ? label : Ui.Row(8, label, Ui.Dot(item.Dot));
            row.Children.Add(left.At(0));
            if (item.Meta != null) row.Children.Add(Ui.Caption(item.Meta, Palette.TextMuted).At(1));
            if (selected) row.Children.Add(Ui.Icon(Icons.Check, Sizes.IconSm, Palette.Accent).At(2));
            var p = new Pressable(Look.Ghost, row)
            {
                Height = Sizes.ControlMd,
                Padding = new Thickness(10, 0),
                CornerRadius = new CornerRadius(Radius.Sm),
                ForceHover = index == _hot,
                IsTabStop = false,
            };
            p.Click += (_, _) => Choose(index);
            _menu.Children.Add(p);
        }
    }

    private void Choose(int index)
    {
        if (index < 0 || index >= _items.Count) return;
        _selected = _items[index].Value;
        ShowValue();
        SetOpen(false);
        Selected?.Invoke(_selected);
    }

    private void OnTriggerKey(object? sender, KeyEventArgs e)
    {
        if (IsOpen) { OnMenuKey(sender, e); return; }
        var i = IndexOf(_selected);
        switch (e.Key)
        {
            case Key.Down when i < _items.Count - 1: Choose(i + 1); e.Handled = true; break;
            case Key.Up when i > 0: Choose(i - 1); e.Handled = true; break;
        }
    }

    private void OnMenuKey(object? sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Down: _hot = Math.Min(_items.Count - 1, _hot + 1); Rebuild(); e.Handled = true; break;
            case Key.Up: _hot = Math.Max(0, _hot - 1); Rebuild(); e.Handled = true; break;
            case Key.Enter or Key.Space: Choose(_hot); e.Handled = true; break;
            case Key.Escape: SetOpen(false); e.Handled = true; break;
        }
    }
}
