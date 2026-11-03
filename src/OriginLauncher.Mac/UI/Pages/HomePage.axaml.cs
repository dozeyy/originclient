using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using OriginLauncher.App.UI;
using OriginLauncher.Mac.UI.Controls;

namespace OriginLauncher.Mac.UI.Pages;

public sealed partial class HomePage : UserControl
{
    public HomePage()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ReportStripTop();
        HomeCard.SizeChanged += (_, _) => ReportStripTop();
    }

    /// <summary>Where Home's strip starts (window coordinates); NaN before layout.</summary>
    internal double StripTop { get; private set; } = double.NaN;

    // The numeral stands in the band between the bar and the strip: tell the art stage where the strip starts.
    // Under 640 tall the strip sits 24 from the bottom instead of 32 (the WPF page's compact rule).
    private void ReportStripTop()
    {
        var h = Bounds.Height;
        if (h <= 0 || HomeCard.Bounds.Height <= 0) return;
        var bottom = h < 640 ? 24 : 32;
        if (Math.Abs(HomeCard.Margin.Bottom - bottom) > 0.1)
        {
            HomeCard.Margin = new Thickness(bottom, 0, bottom, bottom);
            return; // the margin change lays out again and reports
        }
        StripTop = h - (HomeCard.Margin.Bottom + HomeCard.Bounds.Height);
        ArtStage.SetStripTop(StripTop);
    }

    /// <summary>The version on the card: the number, the family name and the family art thumbnail.</summary>
    public void SetVersion(string version)
    {
        var group = FamilyTheme.GroupOf(version);
        VersionButtonText.Text = version;
        VersionTagline.Text = group.Tagline;
        (VersionThumb.Source as IDisposable)?.Dispose();
        // 48 px tile, decoded at twice that for Retina.
        VersionThumb.Source = ArtStage.Decode($"{group.Key}-tc.jpg", decodeWidth: 96);
    }

    /// <summary>The status line and Play: ready (signed in, accent dot, Play live) or waiting with <paramref name="status"/>.</summary>
    public void SetReady(bool ready, string status)
    {
        PlayButton.IsEnabled = ready;
        StatusText.Text = status;
        StatusDot.Fill = Find<IBrush>(ready ? "Brush.Accent" : "Brush.TextDim");
    }

    public bool MyMods
    {
        get => MyModsSwitch.IsChecked == true;
        set => MyModsSwitch.IsChecked = value;
    }

    internal Button Play => PlayButton;

    private T? Find<T>(string key) where T : class => this.TryFindResource(key, out var v) ? v as T : null;
}
