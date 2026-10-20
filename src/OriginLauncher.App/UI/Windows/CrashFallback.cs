using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace OriginLauncher.App.UI.Windows;

/// <summary>
/// The crash windows' safety net. Their normal layout is XAML built from the theme (Brush.*, Text.*, Button.*),
/// so if App.xaml's dictionaries are what failed to load, InitializeComponent throws and the player would get
/// no dialog in exactly the case the window exists for (CrashReporter swallows a throwing window). Each window
/// catches that and builds the same content here in code, with literal colours and system fonts and nothing
/// looked up from resources. It is deliberately plain: the same text and the same buttons, not the same look.
/// </summary>
internal static class CrashFallback
{
    // The launcher's dark neutrals as literals (Theme/Colors.xaml has the named versions).
    private static readonly Brush Bg = Frozen(0x11, 0x11, 0x11);
    private static readonly Brush Well = Frozen(0x1A, 0x1A, 0x1A);
    private static readonly Brush Edge = Frozen(0x3A, 0x3A, 0x3A);
    private static readonly Brush Ink = Frozen(0xF0, 0xF0, 0xF0);
    private static readonly Brush Dim = Frozen(0xB0, 0xB0, 0xB0);
    private static readonly Brush Muted = Frozen(0x80, 0x80, 0x80);
    private static readonly Brush Primary = Frozen(0xE0, 0xE0, 0xE0);
    private static readonly Brush OnPrimary = Frozen(0x11, 0x11, 0x11);
    private static readonly FontFamily Ui = new("Segoe UI");
    private static readonly FontFamily Mono = new("Consolas");

    private static Brush Frozen(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }

    /// <summary>Window chrome: background, font, and a margin-24 grid with the given rows (one star row).</summary>
    public static Grid Frame(Window window, int starRow, int rows)
    {
        window.Background = Bg;
        window.FontFamily = Ui;
        var grid = new Grid { Margin = new Thickness(24) };
        for (var i = 0; i < rows; i++)
            grid.RowDefinitions.Add(new RowDefinition { Height = i == starRow ? new GridLength(1, GridUnitType.Star) : GridLength.Auto });
        window.Content = grid;
        return grid;
    }

    public static TextBlock Heading(string text) => new()
    { Text = text, Foreground = Ink, FontSize = 24, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap };

    public static TextBlock Body(Thickness margin) => new()
    { Foreground = Dim, FontSize = 13.5, LineHeight = 20, TextWrapping = TextWrapping.Wrap, Margin = margin };

    public static TextBlock Caption(Thickness margin) => new()
    { Foreground = Muted, FontSize = 11, TextWrapping = TextWrapping.Wrap, Margin = margin };

    public static TextBlock Accent(Thickness margin) => new()
    { Foreground = Ink, FontSize = 13.5, FontWeight = FontWeights.SemiBold, Margin = margin, Visibility = Visibility.Collapsed };

    /// <summary>The read-only monospace report box inside a bordered panel; returns the box, adds the panel to the grid row.</summary>
    public static TextBox Details(Grid grid, int row, Thickness margin, double fontSize)
    {
        var box = new TextBox
        {
            IsReadOnly = true, IsReadOnlyCaretVisible = true, AcceptsReturn = true,
            Background = Brushes.Transparent, Foreground = Ink, BorderThickness = new Thickness(0),
            FontFamily = Mono, FontSize = fontSize, Padding = new Thickness(16), TextWrapping = TextWrapping.NoWrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        };
        var panel = new Border
        {
            Margin = margin, Background = Well, BorderBrush = Edge, BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4), Child = box,
        };
        Grid.SetRow(panel, row);
        grid.Children.Add(panel);
        return box;
    }

    /// <summary>A bordered panel for the culprit list; collapsed until the window fills it.</summary>
    public static Border Card(Thickness margin, UIElement child) => new()
    {
        Margin = margin, Background = Well, BorderBrush = Edge, BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(4), Padding = new Thickness(16), Child = child, Visibility = Visibility.Collapsed,
    };

    /// <summary>A flat button with its own template (no system chrome, no resources): outlined, or filled when primary.</summary>
    public static Button Btn(string content, bool primary, RoutedEventHandler click)
    {
        var border = new FrameworkElementFactory(typeof(Border), "B");
        border.SetValue(Border.BackgroundProperty, primary ? Primary : Brushes.Transparent);
        border.SetValue(Border.BorderBrushProperty, primary ? Primary : Edge);
        border.SetValue(Border.BorderThicknessProperty, new Thickness(1));
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(8));
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        presenter.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        presenter.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        presenter.SetValue(FrameworkElement.MarginProperty, new Thickness(16, 0, 16, 0));
        border.AppendChild(presenter);

        var template = new ControlTemplate(typeof(Button)) { VisualTree = border };
        var hover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hover.Setters.Add(new Setter(Border.BorderBrushProperty, Ink, "B"));
        template.Triggers.Add(hover);
        var off = new Trigger { Property = UIElement.IsEnabledProperty, Value = false };
        off.Setters.Add(new Setter(UIElement.OpacityProperty, 0.4));
        template.Triggers.Add(off);

        var button = new Button
        {
            Content = content, Template = template, Height = 40, MinWidth = 110, Cursor = System.Windows.Input.Cursors.Hand,
            Foreground = primary ? OnPrimary : Ink, FontSize = 13.5, FontWeight = primary ? FontWeights.SemiBold : FontWeights.Normal,
        };
        button.Click += click;
        return button;
    }

    public static StackPanel ButtonRow(Grid grid, int row)
    {
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        Grid.SetRow(buttons, row);
        grid.Children.Add(buttons);
        return buttons;
    }
}
