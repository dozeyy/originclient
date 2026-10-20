using System.Windows;
using System.Windows.Controls;
using OriginLauncher.App.Core.Launch;
using OriginLauncher.App.UI.Windows;
using Xunit;

namespace OriginLauncher.UiTests;

/// <summary>
/// The crash windows must show something even when App.xaml's theme dictionaries are what failed to load
/// (CrashReporter swallows a window that throws, so the player would get no dialog at all). One test, because a
/// process can have only one Application: it starts with EMPTY resources (the broken-theme case), checks both
/// windows fall back to the in-code layout with the same text and buttons, then merges the real theme and checks
/// they take the normal themed path. Windows are constructed and inspected, never shown.
/// </summary>
public class CrashWindowFallbackTests
{
    private static readonly string[] ThemeFiles =
    [
        "Colors", "Typography", "Spacing", "Buttons", "Icons", "Inputs", "Brand", "Menus", "Social", "Surfaces", "ToolTips",
    ];

    private static void OnSta(Action body)
    {
        Exception? failure = null;
        var thread = new Thread(() => { try { body(); } catch (Exception e) { failure = e; } });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        if (failure != null) throw new Xunit.Sdk.XunitException("STA body failed: " + failure);
    }

    private static string[] ButtonLabels(Window w) =>
        Descendants(w).OfType<Button>().Select(b => b.Content?.ToString() ?? "").ToArray();

    private static IEnumerable<DependencyObject> Descendants(DependencyObject root)
    {
        foreach (var child in LogicalTreeHelper.GetChildren(root).OfType<DependencyObject>())
        {
            yield return child;
            foreach (var d in Descendants(child)) yield return d;
        }
    }

    [Fact]
    public void CrashWindowsStillBuildWhenTheThemeIsMissing_AndUseTheThemeWhenItLoads()
    {
        OnSta(() =>
        {
            var app = new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
            Assert.Empty(app.Resources.MergedDictionaries); // the broken-theme case

            // --- fallback layout --------------------------------------------------------------------
            var plain = new CrashWindow("REPORT BODY", @"C:\logs\crash.txt", fatal: true);
            Assert.True(plain.UsingFallbackLayout);
            Assert.NotNull(plain.Content);
            Assert.Equal("Origin Launcher has to close", plain.Heading.Text);
            Assert.Equal("REPORT BODY", plain.DetailsBox.Text);
            Assert.Contains(@"C:\logs\crash.txt", plain.SavedTo.Text);
            Assert.Equal(new[] { "Copy details", "Close" }, ButtonLabels(plain));

            var recovered = new CrashWindow("X", null, fatal: false);
            Assert.Equal(new[] { "Copy details", "Continue" }, ButtonLabels(recovered));
            Assert.Equal(Visibility.Collapsed, recovered.SavedTo.Visibility);

            var analysis = new CrashAnalysis([new CrashCulprit("badmod", "badmod-1.0.jar", true)], "A mod crashed.", "stack...");
            var report = new CrashReportWindow(analysis, null, who: "Steve - Minecraft 1.21.1");
            Assert.True(report.UsingFallbackLayout);
            Assert.Equal(new[] { "Show details", "Copy report", "Open logs folder", "Close", "Relaunch without badmod" }, ButtonLabels(report));
            Assert.Equal("Steve - Minecraft 1.21.1", report.WhoText.Text);
            Assert.Equal(Visibility.Visible, report.WhoText.Visibility);
            Assert.Equal(Visibility.Visible, report.CulpritPanel.Visibility);
            Assert.Single(report.CulpritList.Children);
            Assert.Equal("stack...", report.DetailsBox.Text);
            Assert.Equal("A mod didn't load: badmod", report.HeadlineText.Text);
            Assert.Equal(Visibility.Collapsed, report.DetailsPanel.Visibility); // technical detail starts folded away

            var unknown = new CrashReportWindow(new CrashAnalysis([], "Unknown.", null), null);
            Assert.Equal(Visibility.Collapsed, unknown.CulpritPanel.Visibility);
            Assert.Equal(Visibility.Collapsed, unknown.WhoText.Visibility);

            // A fallback window lays out and renders without throwing, even though it is never shown.
            plain.Measure(new Size(660, 560));
            plain.Arrange(new Rect(0, 0, 660, 560));
            plain.UpdateLayout();

            // --- themed layout, once the real dictionaries are merged --------------------------------
            foreach (var name in ThemeFiles)
                app.Resources.MergedDictionaries.Add(new ResourceDictionary
                {
                    Source = new Uri($"/OriginLauncher.App;component/Theme/{name}.xaml", UriKind.Relative),
                });

            var themed = new CrashWindow("REPORT", null, fatal: true);
            Assert.False(themed.UsingFallbackLayout);
            Assert.Equal(new[] { "Copy details", "Close" }, ButtonLabels(themed));
            var themedReport = new CrashReportWindow(analysis, null);
            Assert.False(themedReport.UsingFallbackLayout);
            Assert.Equal(new[] { "Show details", "Copy report", "Open logs folder", "Close", "Relaunch without badmod" }, ButtonLabels(themedReport));
        });
    }
}
