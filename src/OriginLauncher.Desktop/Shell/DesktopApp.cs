using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media;
using Avalonia.Styling;
using Avalonia.Themes.Fluent;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Shell;

// The Avalonia application: Fluent (dark) underneath only for the stock text box, scroll bars and popups,
// re-tinted with the launcher's tokens; every button, card, tab and switch is the launcher's own (Kit/).
public sealed class DesktopApp : Application
{
    /// <summary>Set by the real run (not render runs): the window to open once the platform is up.</summary>
    public static Func<MainWindow>? CreateWindow { get; set; }

    public override void Initialize()
    {
        RequestedThemeVariant = ThemeVariant.Dark;
        Styles.Add(new FluentTheme());
        Styles.Add(ToolTips.Style()); // the Windows launcher's hover-label timing and placement (Theme/Tokens.cs)
        var r = Resources;
        // Fluent's own resource keys, pointed at the Workbench tokens (text boxes, scroll bars, tooltips, menus).
        r["ContentControlThemeFontFamily"] = Assets.Inter;
        r["SystemAccentColor"] = Palette.Accent.Color;
        r["SystemAccentColorLight1"] = Palette.AccentHover.Color;
        r["SystemAccentColorDark1"] = Palette.AccentPress.Color;
        r["TextControlBackground"] = Palette.Field;
        r["TextControlBackgroundPointerOver"] = Palette.Field;
        r["TextControlBackgroundFocused"] = Palette.Field;
        r["TextControlBorderBrush"] = Palette.LineStrong;
        r["TextControlBorderBrushPointerOver"] = Palette.LineHover;
        r["TextControlBorderBrushFocused"] = Palette.AccentLine;
        r["TextControlForeground"] = Palette.Text;
        r["TextControlForegroundPointerOver"] = Palette.Text;
        r["TextControlForegroundFocused"] = Palette.Text;
        r["TextControlPlaceholderForeground"] = Palette.TextMuted;
        r["TextControlPlaceholderForegroundPointerOver"] = Palette.TextMuted;
        r["TextControlPlaceholderForegroundFocused"] = Palette.TextMuted;
        r["TextControlSelectionHighlightColor"] = Palette.AccentSoft;
        r["TextControlThemeMinHeight"] = Sizes.ControlLg;
        r["ControlCornerRadius"] = new CornerRadius(Radius.Md);
        r["OverlayCornerRadius"] = new CornerRadius(Radius.Md);
        r["ToolTipBackground"] = Palette.Card;
        r["ToolTipForeground"] = Palette.Text;
        r["ToolTipBorderBrush"] = Palette.LineStrong;
        r["ToolTipContentThemeFontSize"] = TypeScale.Caption;
        r["ScrollBarThumbFill"] = Palette.LineStrong;
        r["ScrollBarThumbFillPointerOver"] = Palette.LineHover;
        r["ScrollBarThumbFillPressed"] = Palette.TextMuted;
        r["FlyoutPresenterBackground"] = Palette.Card;
        r["FlyoutBorderThemeBrush"] = Palette.LineStrong;
        r["MenuFlyoutPresenterBackground"] = Palette.Card;
        r["MenuFlyoutPresenterBorderBrush"] = Palette.LineStrong;
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop && CreateWindow != null)
            desktop.MainWindow = CreateWindow();
        base.OnFrameworkInitializationCompleted();
    }
}
