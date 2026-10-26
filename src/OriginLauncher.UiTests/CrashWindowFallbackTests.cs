using System.IO;
using System.Windows;
using System.Windows.Controls;
using OriginLauncher.App.Core;
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
            // The selector test (below) saves a pick through SettingsStore: keep every launcher path in a scratch folder
            // and every animation instant, before anything resolves OriginPaths.Root.
            var scratch = Directory.CreateTempSubdirectory("origin-uitests").FullName;
            OriginPaths.UseScratchRoot(Path.Combine(scratch, "data"));
            OriginLauncher.App.UI.Motion.Off = true;

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

            // Crash Report V3: verdicts from the real engine. strong + one added mod = the fix button carries the fix.
            CrashVerdict Diagnose(string? log, string? crash) => CrashEngine.Diagnose(new CrashEvidence(1, false, true, log, null, crash, null,
                [new CrashModEntry("badmod", "badmod-1.0.jar", true), new CrashModEntry("originclient", "originclient.jar", false)], 4096, 16384));
            var strong = Diagnose("[main/ERROR]: Mixin apply for mod badmod failed badmod.mixins.json:BadMixin from mod badmod -> net.minecraft.class_310: InvalidInjectionException", null);
            Assert.Equal(CrashConfidence.Strong, strong.Finding.Confidence);
            string[] reportButtons = ["Show details", "Copy report", "Open logs folder", "Close", "Try once without my mods", strong.SafeFix!.Label];
            var report = new CrashReportWindow(strong, null, who: "Steve - Minecraft 1.21.1", buildReport: () => "r");
            Assert.True(report.UsingFallbackLayout);
            Assert.Equal(reportButtons, ButtonLabels(report));
            Assert.Equal(Visibility.Visible, report.ActionButton.Visibility);
            Assert.Equal(Visibility.Collapsed, report.TryButton.Visibility); // strong: the fix, not the blind try
            Assert.Equal("Steve - Minecraft 1.21.1", report.WhoText.Text);
            Assert.Equal(Visibility.Visible, report.WhoText.Visibility);
            Assert.Equal(Visibility.Visible, report.CulpritPanel.Visibility);
            Assert.Single(report.CulpritList.Children);
            Assert.Equal("strong", report.ConfidenceText.Text);
            Assert.Equal(strong.Finding.Headline, report.HeadlineText.Text);
            Assert.Contains("mixin-apply", report.DetailsBox.Text);
            Assert.Equal(Visibility.Collapsed, report.DetailsPanel.Visibility); // technical detail starts folded away

            // unknown: no fix button, the one-time try without added mods instead, and the word "unknown".
            var unknown = new CrashReportWindow(Diagnose(null, "---- Minecraft Crash Report ----\nDescription: x\n\njava.lang.IllegalStateException: x\n"), null);
            Assert.Equal("unknown", unknown.ConfidenceText.Text);
            Assert.Equal(Visibility.Collapsed, unknown.ActionButton.Visibility);
            Assert.Equal(Visibility.Visible, unknown.TryButton.Visibility);
            Assert.Empty(unknown.CulpritList.Children);
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
            var themedReport = new CrashReportWindow(strong, null);
            Assert.False(themedReport.UsingFallbackLayout);
            Assert.Equal(reportButtons, ButtonLabels(themedReport));
            Assert.Equal("strong", themedReport.ConfidenceText.Text);

            // Same Application, so the Home blank-after-picker regression runs here (HomeSceneRegression.cs).
            HomeSceneRegression.Run();
            // ...and the restyled dropdowns' keyboard behaviour (DropdownKeyboardRegression.cs).
            DropdownKeyboardRegression.Run();

            // ...and so does the two-step version selector (VersionSelectorUiTests.cs).
            VersionSelectorUiTests.Run(scratch);
        });
    }
}
