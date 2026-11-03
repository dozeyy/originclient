using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using OriginLauncher.App.Core;
using OriginLauncher.App.Core.Compat;
using OriginLauncher.App.Core.Components;
using OriginLauncher.App.Core.GameFolders;
using OriginLauncher.App.Core.Mods;
using OriginLauncher.App.Core.Profiles;
using OriginLauncher.App.Core.Versions;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Shell;
using OriginLauncher.Desktop.Theme;
using CoreFix = OriginLauncher.App.Core.Components.FixCompatibility;

namespace OriginLauncher.Desktop.Overlays;

public enum FixChoice { Cancel, LaunchAnyway, Fix }

// The Fix Compatibility prompt over a launch Play refused (the Windows launcher's HomePage.Fix.cs +
// FixCompatibilityOverlay). The prompt shows the refused mods at once; the plan (and any Modrinth lookup, mod ids
// only) runs off the UI thread; nothing is written until the player presses Fix, and then only a COPY is made
// (Core/Components/FixCompatibility -> Core/Compat/RepairExecutor.Copy). The status line underneath keeps the
// refusal, so Play there still means "start anyway" (LaunchOverride). It is a dialog in Confirm's style.
public static class FixCompatibility
{
    private const string FixFoot = "Your original stays exactly as it is: the fix is made in a copy, which Play then starts.";

    /// <summary>True while a fixed copy is being made: Play/Join are off and no second prompt opens.</summary>
    public static bool Running { get; private set; }

    private static bool _prompting;

    /// <summary>Play refused a set of mods: show the prompt, plan the fix, act on the player's answer.</summary>
    public static async Task OfferAsync(MainWindow window, IncompatibleModsException refused, string version, ModProfile? profile)
    {
        if (Running || _prompting) return;
        _prompting = true;
        FixPrompt? prompt = null;
        try
        {
            prompt = FixPrompt.Open(window, refused.Names, version);

            var store = VersionManager.SharedComponents;
            var source = new FixSource(version, profile, OriginPaths.ModsDir(version),
                profile == null ? null : OriginPaths.ProfileModsDir(version, profile.Id),
                ModManager.IsManaged, ModManager.IsOriginProvisioned, ModManager.IsOriginsCopy);
            FixProposal? proposal = null;
            using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(20)))
            {
                try
                {
                    proposal = await Task.Run(() => CoreFix.PlanAsync(source, store, store.Http, cts.Token), cts.Token);
                }
                catch (OperationCanceledException)
                {
                    // took too long (a slow network): the prompt says there is no fix it could prepare
                }
            }

            prompt.ShowPlan(proposal, profile);

            switch (await prompt.Choice)
            {
                case FixChoice.LaunchAnyway:
                    _prompting = false;
                    await window.Home.Launches.PlayOrFocusAsync(LauncherState.SelectedVersion); // the refusal is still on the line
                    break;
                case FixChoice.Fix when proposal is { CanFix: true }:
                    _prompting = false;
                    await ApplyAsync(window, proposal);
                    break;
            }
        }
        catch (Exception ex)
        {
            // The prompt is a helper over a refusal the status line already explains: it never takes Home down.
            System.Diagnostics.Debug.WriteLine($"[Home] Fix Compatibility failed: {ex}");
            prompt?.Dismiss();
        }
        finally
        {
            _prompting = false;
        }
    }

    /// <summary>
    /// The import report's "Fix Compatibility" on a profile it says can't start. The same check Play runs decides, off
    /// the UI thread, and the same prompt opens. Nothing to fix any more (the check now passes): the status line says so
    /// and nothing starts.
    /// </summary>
    public static async Task Open(MainWindow window, string version, string profileId)
    {
        if (Running || _prompting) return;
        var profile = SettingsStore.Load().ModProfiles.FirstOrDefault(p => p.Id == profileId && p.Version == version);
        if (profile == null) return;
        LaunchVerdict verdict;
        try
        {
            verdict = await Task.Run(() => LaunchPreflight.Evaluate(version, OriginPaths.ModsDir(version),
                OriginPaths.ProfileModsDir(version, profile.Id), true, ModManager.IsManaged, ModManager.IsOriginProvisioned,
                confirmOrigin: ModManager.IsOriginsCopy));
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            verdict = LaunchVerdict.Clear; // fail-soft, as the launch check is: Play checks again
        }
        if (verdict.Blocking.Count == 0)
        {
            window.Home.Launches.NoteSelected($"\"{profile.Name}\" passes the start check now. Press Play to start it.", LaunchTone.Neutral);
            return;
        }
        await OfferAsync(window, new IncompatibleModsException(version, verdict.BlockingNames, verdict.Blocking, verdict.SetKey),
            version, profile);
    }

    private static async Task ApplyAsync(MainWindow window, FixProposal proposal)
    {
        if (Running) return;
        // Play (and Join) stay off while the copy is made, so no second refusal can start a second fix;
        // Core's Apply refuses one anyway.
        Running = true;
        window.Home.RefreshPlayState();
        RepairCopyResult result;
        try
        {
            window.Home.Launches.NoteSelected("Making a fixed copy...", LaunchTone.Neutral);
            var store = VersionManager.SharedComponents;
            using var cts = new CancellationTokenSource(TimeSpan.FromMinutes(2));
            result = await Task.Run(() => CoreFix.Apply(
                proposal, OriginPaths.ProfilesDir(proposal.Source.Version), OriginPaths.RepairJournals, SettingsStore.Update,
                new ModrinthDependencyFetcher(store.Http, cts.Token),
                id => SettingsStore.Load().ModProfiles.Any(p => p.Id == id),
                OriginPaths.ProfilesDir));
        }
        finally
        {
            Running = false;
            window.Home.RefreshPlayState();
        }
        if (!result.Ok)
        {
            window.Home.Launches.NoteSelected(result.Error!, LaunchTone.Error);
            return;
        }
        LauncherState.ReloadSettings();
        window.Home.Launches.NoteSelected($"Made \"{result.ProfileName}\". Starting it...", LaunchTone.Success);
        await window.Home.Launches.PlayOrFocusAsync(LauncherState.SelectedVersion);
    }

    // ---- Render runs ----

    /// <summary>The prompt in a given state, without planning (render runs pass a proposal the real planner made).</summary>
    internal static FixPrompt TourPrompt(MainWindow window, IReadOnlyList<string> refusedNames, string version,
        FixProposal? proposal, ModProfile? profile)
    {
        var prompt = FixPrompt.Open(window, refusedNames, version);
        prompt.ShowPlan(proposal, profile);
        return prompt;
    }

    // The prompt itself: title, the refused mods, the plan (scrolls past 232), the foot line, three buttons. It opens
    // with "Looking for a fix..." and Fix off; the plan fills it in a moment later.
    internal sealed class FixPrompt
    {
        private readonly MainWindow _window;
        private readonly TaskCompletionSource<FixChoice> _done = new();
        private readonly TextBlock _heading = LabelText("Looking for a fix...");
        private readonly StackPanel _changes = Ui.Column(0);
        private readonly TextBlock _noFixHeading = LabelText("Not fixed by this");
        private readonly StackPanel _noFix = Ui.Column(0);
        private readonly TextBlock _foot = Ui.Wrap(Ui.Caption("", Palette.TextMuted));
        private readonly Pressable _cancel = Ui.Button("Cancel", Look.Ghost, height: Sizes.ControlMd);
        private readonly Pressable _anyway = Ui.Button("Launch anyway", Look.Secondary, height: Sizes.ControlMd);
        private readonly Pressable _fix = Ui.Button("Fix Compatibility", Look.Primary, height: Sizes.ControlMd);
        private readonly DateTime _opened = DateTime.UtcNow;
        private DateTime _lastHeldKey;
        private bool _armed;
        private FixChoice _choice = FixChoice.Cancel;

        public Task<FixChoice> Choice => _done.Task;

        private FixPrompt(MainWindow window, string title, string message)
        {
            _window = window;
            var titleText = Ui.Wrap(Ui.Title(title));
            var body = Ui.Body(message);
            body.Margin = new Thickness(0, Space.S2, 0, 0);

            _changes.Margin = new Thickness(0, 6, 0, 0);
            _noFixHeading.Margin = new Thickness(0, 10, 0, 0);
            _noFixHeading.IsVisible = false;
            _noFix.Margin = new Thickness(0, 6, 0, 0);
            var list = new ScrollViewer
            {
                MaxHeight = Sizes.FixListMaxH, Margin = new Thickness(0, Space.S4, 0, 0),
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                Content = Ui.Column(0, _heading, _changes, _noFixHeading, _noFix),
            };
            _foot.Margin = new Thickness(0, Space.S3, 0, 0);
            _foot.IsVisible = false;

            foreach (var b in new[] { _cancel, _anyway, _fix }) b.MinWidth = 96;
            _fix.IsEnabled = false;
            var footer = Ui.Row(Space.S2, _cancel, _anyway, _fix);
            footer.HorizontalAlignment = HorizontalAlignment.Right;
            footer.Margin = new Thickness(0, Space.S6, 0, 0);
            _cancel.Click += (_, _) => Resolve(FixChoice.Cancel);
            _anyway.Click += (_, _) => Resolve(FixChoice.LaunchAnyway);
            _fix.Click += (_, _) => Resolve(FixChoice.Fix);

            var content = Ui.Column(0, titleText, body, list, _foot, footer);
            // The Enter that pressed Play may still be repeating when the prompt opens: repeats never press anything.
            content.AddHandler(InputElement.KeyDownEvent, OnKeyDownTunnel, RoutingStrategies.Tunnel);
            content.AddHandler(InputElement.KeyUpEvent, (_, e) => { if (e.Key is Key.Enter or Key.Space) _armed = true; }, RoutingStrategies.Tunnel);
            // Esc (MainWindow) and every button close the dialog; closing is the answer.
            window.OpenDialog(content, dismissable: false, onClosed: () => _done.TrySetResult(_choice));
            Dispatcher.UIThread.Post(() => _cancel.Focus(), DispatcherPriority.Input);
        }

        public static FixPrompt Open(MainWindow window, IReadOnlyList<string> refusedNames, string version)
        {
            var names = refusedNames.Count <= 3
                ? string.Join(", ", refusedNames)
                : string.Join(", ", refusedNames.Take(3)) + $" and {refusedNames.Count - 3} more";
            return new FixPrompt(window,
                $"{(refusedNames.Count == 1 ? "A mod" : "Some mods")} won't start on {version}",
                names + " would stop the game. The check reads each mod's own description, which can be wrong.");
        }

        /// <summary>The prompt's second state: what the fix would change, or plainly why there is none.</summary>
        public void ShowPlan(FixProposal? proposal, ModProfile? profile)
        {
            if (proposal == null)
                Fill("No fix is ready", [], ["Origin couldn't prepare a fix just now. Launch anyway tries the mods as they are."], "", canFix: false);
            else if (proposal.CanFix)
                Fill(profile == null ? "Fix Compatibility makes a fixed profile from your mods that will:" : $"Fix Compatibility makes a copy of \"{profile.Name}\" that will:",
                    proposal.Changes, proposal.NoFix, FixFoot, canFix: true);
            else
                Fill("Why there is no fix", [], proposal.NoFix, "", canFix: false);
        }

        private void Fill(string heading, IReadOnlyList<string> changes, IReadOnlyList<string> noFix, string foot, bool canFix)
        {
            if (_done.Task.IsCompleted) return;
            _heading.Text = heading;
            _changes.Children.Clear();
            foreach (var c in changes) _changes.Children.Add(Line(c, Palette.Text));
            _changes.IsVisible = changes.Count > 0;
            _noFix.Children.Clear();
            foreach (var c in noFix) _noFix.Children.Add(Line(c, Palette.TextDim));
            _noFixHeading.IsVisible = canFix && noFix.Count > 0;
            _foot.Text = foot;
            _foot.IsVisible = foot.Length > 0;
            _fix.IsEnabled = canFix;
            _fix.IsVisible = canFix;
            Dispatcher.UIThread.Post(() => (canFix ? _fix : _anyway).Focus(), DispatcherPriority.Input);
        }

        /// <summary>Closes an open prompt as Cancel.</summary>
        public void Dismiss() => Resolve(FixChoice.Cancel);

        private void Resolve(FixChoice choice)
        {
            if (_done.Task.IsCompleted) return;
            _choice = choice;
            _window.CloseDialog();
            _done.TrySetResult(choice); // in case the dialog was already replaced
        }

        // Swallows a held Enter/Space from before the prompt opened: until that key is let go, a key-down within 150 ms
        // of the last one (a repeat) or in the first 600 ms is ignored.
        private void OnKeyDownTunnel(object? sender, KeyEventArgs e)
        {
            if (_armed || e.Key is not (Key.Enter or Key.Space)) return;
            var now = DateTime.UtcNow;
            if (now - _opened < TimeSpan.FromMilliseconds(600) || now - _lastHeldKey < TimeSpan.FromMilliseconds(150))
            {
                _lastHeldKey = now;
                e.Handled = true;
                return;
            }
            _armed = true;
        }

        private static TextBlock LabelText(string text) =>
            Ui.Wrap(Ui.Text(text, TypeScale.Label, FontWeight.SemiBold, Palette.TextMuted));

        private static TextBlock Line(string text, IBrush brush)
        {
            var t = Ui.Body("- " + text, brush);
            t.Margin = new Thickness(0, 0, 0, 4);
            return t;
        }

        // ---- Render runs ----

        internal Pressable FixButton => _fix;
        internal Pressable AnywayButton => _anyway;
    }
}
