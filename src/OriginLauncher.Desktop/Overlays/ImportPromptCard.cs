using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using OriginLauncher.App.Core.Import;
using OriginLauncher.App.UI.Controls;
using OriginLauncher.Desktop.Kit;
using OriginLauncher.Desktop.Theme;

namespace OriginLauncher.Desktop.Overlays;

// The first-run "bring your setups over?" card over Home (port of UI/Controls/ImportPromptCard.xaml). It sits in
// Home's card slot (HomePage.ShowCard) under a scrim; ImportView decides when it shows and wires the three buttons.
// No entrance animation: the card is there, and works, from the first frame (opening any screen must feel instant).
public sealed class ImportPromptCard : Panel
{
    // At most this many launcher chips; the rest fold into one "+N more" chip.
    private const int MaxChips = 5;

    public event Action? ImportRequested;
    public event Action? ChooseRequested;
    public event Action? SkipRequested;

    public Pressable ImportButton { get; }
    public Pressable ChooseButton { get; }
    public Pressable SkipButton { get; }

    private readonly Border _card;

    public ImportPromptCard(string question, IReadOnlyCollection<DetectedInstance>? setups)
    {
        Children.Add(new Border { Background = Palette.Scrim });

        var content = new StackPanel();
        content.Children.Add(Ui.Logo(Sizes.LogoHero).Align(HorizontalAlignment.Left));
        content.Children.Add(Ui.Wrap(Ui.Display("Welcome to Origin")).Margin(0, Space.S4, 0, 0));
        content.Children.Add(Ui.Wrap(Ui.Text(question, TypeScale.Title, FontWeight.SemiBold)).Margin(0, Space.S3, 0, 0));
        var lead = Ui.Wrap(Ui.Body("Your worlds, mods, servers and settings come over as Origin profiles. Nothing in the other launchers changes.",
            Palette.TextDim)).Margin(0, Space.S1 + 2, 0, 0);
        lead.MaxWidth = 480;
        lead.HorizontalAlignment = HorizontalAlignment.Left;
        content.Children.Add(lead);

        if (Chips(setups) is { } chips) content.Children.Add(chips);

        ImportButton = Ui.Button("Import everything", Look.Primary, Icons.Import, Sizes.ControlMd).Named("Import everything");
        ImportButton.MinWidth = 120;
        ChooseButton = Ui.Button("Choose", Look.Secondary, null, Sizes.ControlMd).Named("Choose what to import");
        ChooseButton.Margin = new Thickness(Space.S2, 0, 0, 0);
        SkipButton = Ui.Button("Skip for now", Look.Ghost, null, Sizes.ControlMd);
        SkipButton.HorizontalAlignment = HorizontalAlignment.Right;
        ImportButton.Click += (_, _) => ImportRequested?.Invoke();
        ChooseButton.Click += (_, _) => ChooseRequested?.Invoke();
        SkipButton.Click += (_, _) => SkipRequested?.Invoke();

        var buttons = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,Auto,*"), Margin = new Thickness(0, Space.S4, 0, 0) };
        buttons.Children.Add(ImportButton.At(0));
        buttons.Children.Add(ChooseButton.At(1));
        buttons.Children.Add(SkipButton.At(2));
        content.Children.Add(buttons);
        content.Children.Add(Ui.Wrap(Ui.Caption("You can import later from Profiles, or the Import button at the top."))
            .Margin(0, Space.S4, 0, 0));

        _card = new Border
        {
            MaxWidth = 680, Margin = new Thickness(Space.S8, Space.S8 + Space.S12, Space.S8, Space.S8),
            Padding = new Thickness(Space.S8, Space.S6), HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center, Background = Palette.Panel, BorderBrush = Palette.Line,
            BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(Radius.Dialog), BoxShadow = Palette.OverlayShadow,
            Child = content,
        };
        Children.Add(_card);
        Avalonia.Automation.AutomationProperties.SetName(this, question);
    }

    /// <summary>The refusal sentence (or null when Import can run): the card hides while Import can't run, and comes back after.</summary>
    public void SetRefusal(string? sentence) => IsVisible = sentence == null;

    // One chip per launcher, most setups first: its real logo (or initial), its name, and how many setups it holds.
    private static WrapPanel? Chips(IReadOnlyCollection<DetectedInstance>? setups)
    {
        if (setups is null || setups.Count == 0) return null;
        var panel = new WrapPanel { Margin = new Thickness(0, Space.S5, 0, 0) };
        var groups = setups.GroupBy(s => s.Kind)
            .Select(g => (Kind: g.Key, Count: g.Count()))
            .OrderByDescending(g => g.Count)
            .ThenBy(g => ImportFlowModel.LauncherTitle(g.Kind), StringComparer.Ordinal)
            .ToList();
        var shown = groups.Count > MaxChips ? MaxChips - 1 : groups.Count;
        foreach (var (kind, count) in groups.Take(shown))
            panel.Children.Add(Chip(ImportParts.RoundLogo(kind, Sizes.ControlSm), ImportFlowModel.LauncherTitle(kind),
                count == 1 ? "1 setup" : $"{count} setups"));
        if (groups.Count > shown)
        {
            var more = new Border
            {
                Width = Sizes.ControlSm, Height = Sizes.ControlSm, CornerRadius = new CornerRadius(Sizes.ControlSm / 2), Background = Palette.Bg,
                Child = Ui.Text($"+{groups.Count - shown}", TypeScale.Caption, FontWeight.Bold).Align(HorizontalAlignment.Center),
            };
            panel.Children.Add(Chip(more, "more", null));
        }
        return panel;
    }

    private static Border Chip(Control well, string name, string? detail)
    {
        well.Margin = new Thickness(0, 0, Space.S2 + 2, 0);
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(well);
        row.Children.Add(Ui.BodyStrong(name).Align(HorizontalAlignment.Left));
        if (detail != null) row.Children.Add(Ui.Caption(detail, Palette.TextMuted).Margin(Space.S2, 0, 0, 0).Align(HorizontalAlignment.Left));
        // A capsule (radius = height / 2) whose 28 well sits concentric with its round ends.
        const double height = Sizes.ControlSm + 2 * 6;
        return new Border
        {
            Height = height, CornerRadius = new CornerRadius(height / 2), Padding = new Thickness(6, 6, 14, 6),
            Margin = new Thickness(0, 0, Space.S2, Space.S2), Background = Palette.Card, BorderBrush = Palette.Line,
            BorderThickness = new Thickness(1), Child = row,
        };
    }
}
