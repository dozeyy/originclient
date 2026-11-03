using OriginLauncher.App.UI;
using Xunit;

namespace OriginLauncher.Tests;

// Where the launcher window opens (UI/WindowFit): its 1180 x 740 design, shrunk into the monitor's work area and
// centred there, so the top bar and the Play strip are never off screen or under the taskbar.
public class WindowFitTests
{
    private const double W = 1180, H = 740, MinW = 960, MinH = 600;

    [Fact]
    public void A_big_screen_keeps_the_designed_size_centred()
    {
        // 1920 x 1080 at 100 %, a 40 px taskbar at the bottom.
        var fit = WindowFit.Centre(new WindowFit.Box(0, 0, 1920, 1040), W, H, MinW, MinH)!.Value;

        Assert.Equal(new WindowFit.Box(370, 150, W, H), fit.Window);
        Assert.Equal(MinW, fit.MinWidth);
        Assert.Equal(MinH, fit.MinHeight);
    }

    [Fact]
    public void A_1080p_laptop_at_150_percent_fits_inside_the_work_area()
    {
        // 1920 x 1080 at 150 % = 1280 x 720 DIP; the 48 px taskbar takes 32 DIP: work area 1280 x 688.
        var work = new WindowFit.Box(0, 0, 1280, 688);
        var fit = WindowFit.Centre(work, W, H, MinW, MinH)!.Value;

        Assert.Equal(688, fit.Window.Height);
        Assert.Equal(W, fit.Window.Width);
        Assert.Equal(0, fit.Window.Top); // the top bar's window buttons and tabs stay on screen
        Assert.True(fit.Window.Top + fit.Window.Height <= work.Top + work.Height); // Play never under the taskbar
        Assert.Equal(50, fit.Window.Left);
    }

    [Fact]
    public void A_work_area_smaller_than_the_minimum_lowers_the_minimum()
    {
        // 1366 x 768 at 125 % = 1093 x 614 DIP, minus a 30 DIP taskbar: 584 tall, under the 600 minimum.
        var work = new WindowFit.Box(0, 0, 1092.8, 583.2);
        var fit = WindowFit.Centre(work, W, H, MinW, MinH)!.Value;

        Assert.Equal(583.2, fit.MinHeight);
        Assert.Equal(583.2, fit.Window.Height);
        Assert.Equal(0, fit.Window.Top);
        Assert.Equal(MinW, fit.MinWidth); // 960 still fits across
        Assert.Equal(1092.8, fit.Window.Width);
    }

    [Fact]
    public void A_second_monitor_and_a_top_taskbar_are_honoured()
    {
        // A monitor to the left of the primary (negative x) with its taskbar along the top.
        var work = new WindowFit.Box(-1920, 40, 1920, 1040);
        var fit = WindowFit.Centre(work, W, H, MinW, MinH)!.Value;

        Assert.Equal(-1920 + 370, fit.Window.Left);
        Assert.Equal(40 + 150, fit.Window.Top);
    }

    [Fact]
    public void An_unusable_work_area_leaves_the_placement_to_WPF()
    {
        Assert.Null(WindowFit.Centre(new WindowFit.Box(0, 0, 0, 0), W, H, MinW, MinH));
        Assert.Null(WindowFit.Centre(new WindowFit.Box(0, 0, double.NaN, 700), W, H, MinW, MinH));
    }
}
