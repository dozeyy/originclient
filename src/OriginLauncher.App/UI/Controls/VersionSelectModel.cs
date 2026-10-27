using OriginLauncher.App.Core.Versions;

namespace OriginLauncher.App.UI.Controls;

// The version selector's logic with no WPF in it (Phase 3, U3.2), so the keys and the wording are unit-tested on any OS:
//   VersionStatus      what each install state says (a release row, a family card's badge)
//   VersionSelectNav   which step the sheet is on, what has the keyboard, and what each key does
// VersionSelectOverlay draws it and carries out the NavResult it gets back; it decides nothing itself.

/// <summary>Which dot sits in front of a status line (DESIGN-SYSTEM.md 11.9).</summary>
public enum StatusDot
{
    /// <summary>Installed: the success dot.</summary>
    Success,

    /// <summary>An Origin update waits: the info dot (the accent dot would read as "selected" beside the check).</summary>
    Info,

    /// <summary>Attention: something is unfinished.</summary>
    Warning,

    /// <summary>Not installed: the muted dot.</summary>
    Muted,
}

/// <summary>One line of install status: words and a dot.</summary>
public sealed record StatusLine(string Text, StatusDot Dot);

/// <summary>What the selector says about a version or a family, from the cached <see cref="InstallState"/>.</summary>
public static class VersionStatus
{
    /// <summary>
    /// Whether a release in step 2's dropdown wears the quiet emerald dot: it is on disk (an Origin update waiting still
    /// counts). Unknown, missing and half-installed releases show nothing; the dropdown never says "download" (Will,
    /// 2026-10-25: Play installs whatever is needed, so the picker only picks).
    /// </summary>
    public static bool ShowsInstalledDot(InstallState state) => state is InstallState.Installed or InstallState.UpdateReady;

    /// <summary>
    /// The status line under a family card (11.6): "2 of 10 installed" (no bubble around it), or for a family of one release the release's own state.
    /// Null while any release of the family is still unknown, so the count never flickers while the checks come in.
    /// A release with an Origin update waiting still counts as installed.
    /// </summary>
    public static StatusLine? ForFamily(IReadOnlyList<InstallState> releases)
    {
        if (releases.Count == 0 || releases.Any(s => s == InstallState.Unknown)) return null;

        if (releases.Count == 1)
        {
            return releases[0] switch
            {
                InstallState.Installed => new("Installed", StatusDot.Success),
                InstallState.UpdateReady => new("Update ready", StatusDot.Info),
                InstallState.Partial => new("Finish install", StatusDot.Warning),
                _ => new("Not installed", StatusDot.Muted),
            };
        }

        var installed = releases.Count(s => s is InstallState.Installed or InstallState.UpdateReady);
        if (installed > 0) return new($"{installed} of {releases.Count} installed", StatusDot.Success);
        if (releases.Any(s => s == InstallState.Partial)) return new("Finish install", StatusDot.Warning);
        return new("Not installed", StatusDot.Muted);
    }
}

public enum SelectorStep
{
    /// <summary>Step 1: the family cards.</summary>
    Families,

    /// <summary>Step 2: one family's releases.</summary>
    Releases,
}

public enum NavKey { Left, Right, Up, Down, Home, End, Enter, Escape, Backspace }

public enum NavKind
{
    /// <summary>Nothing to do (the key does not apply here, or the move would leave the list).</summary>
    None,

    /// <summary>Step 1: the keyboard moved to another family card. Nothing is picked.</summary>
    FamilyFocused,

    /// <summary>A family with several releases was opened: step 2 shows (<see cref="NavResult.FamilyIndex"/>).</summary>
    OpenedFamily,

    /// <summary>Step 2: another release is the pick (<see cref="NavResult.Version"/>); persist it and let Home follow.</summary>
    Select,

    /// <summary>A family with a single release was chosen: pick it (<see cref="NavResult.Version"/>) and close the sheet.</summary>
    SelectAndClose,

    /// <summary>Step 2: Enter on the panel opens its release dropdown (picking stays one click or arrow away).</summary>
    OpenList,

    /// <summary>Step 2 back to step 1.</summary>
    Back,

    /// <summary>Close the sheet.</summary>
    Close,
}

public readonly record struct NavResult(NavKind Kind, int FamilyIndex = -1, string? Version = null);

/// <summary>
/// The two-step selector as a small state machine. Step 1 is a grid of families (<see cref="Columns"/> wide, reading
/// order); step 2 is one family's panel with a dropdown of its releases, newest first. Keys:
///   step 1  arrows move over the cards, Home / End jump, Enter opens the family, Esc closes
///   step 2  Up / Down move over the releases and pick as they go (a closed dropdown's arrows; Down from "Choose a
///           version" picks the newest), Home / End jump,
///           Enter opens the dropdown, Left or Backspace goes back to step 1, Esc closes the picker
/// Nothing in the picker launches or installs: a pick is the whole job (Will, 2026-10-25).
/// Browsing step 1 picks nothing, and merely opening a family picks nothing: only a release does (so Esc after looking
/// leaves the player's version exactly as it was). A family opens on "Choose a version" with no release chosen, even the
/// one holding the saved version (Will, 2026-10-26): a preselected release could not be picked, since clicking what a
/// dropdown already shows changes nothing.
/// </summary>
public sealed class VersionSelectNav
{
    private readonly IReadOnlyList<VersionGroup> _groups;
    private string? _current;

    public VersionSelectNav(IReadOnlyList<VersionGroup> groups, int columns = 4)
    {
        if (groups.Count == 0) throw new ArgumentException("no families", nameof(groups));
        _groups = groups;
        Columns = Math.Max(1, columns);
    }

    public int Columns { get; }
    public SelectorStep Step { get; private set; } = SelectorStep.Families;
    public int FamilyIndex { get; private set; }
    /// <summary>The release chosen in the open family's dropdown; -1 while it says "Choose a version".</summary>
    public int ReleaseIndex { get; private set; } = -1;

    /// <summary>The player's saved version as the sheet last knew it (a pick updates it).</summary>
    public string? CurrentVersion => _current;

    public VersionGroup Family => _groups[FamilyIndex];
    public string? Release => ReleaseIndex < 0 ? null : Family.Versions[ReleaseIndex];

    /// <summary>Open on step 1 with the keyboard on the family that holds the saved version (the first family if it holds none).</summary>
    public void Open(string? currentVersion)
    {
        _current = currentVersion;
        Step = SelectorStep.Families;
        FamilyIndex = Math.Max(0, IndexOfFamilyHolding(currentVersion));
        ReleaseIndex = -1;
    }

    private int IndexOfFamilyHolding(string? version)
    {
        for (var i = 0; i < _groups.Count; i++)
            if (version != null && _groups[i].Versions.Contains(version)) return i;
        return -1;
    }

    /// <summary>A click (or Enter) on a family card.</summary>
    public NavResult ChooseFamily(int index)
    {
        if (index < 0 || index >= _groups.Count) return new(NavKind.None);
        FamilyIndex = index;
        if (_groups[index].Versions.Count == 1)
        {
            ReleaseIndex = 0;
            _current = _groups[index].Versions[0];
            return new(NavKind.SelectAndClose, index, _current);
        }
        Step = SelectorStep.Releases;
        ReleaseIndex = -1;
        return new(NavKind.OpenedFamily, index);
    }

    /// <summary>A click on a release row, or the highlight moving to it.</summary>
    public NavResult ChooseRelease(int index)
    {
        var versions = Family.Versions;
        if (Step != SelectorStep.Releases || index < 0 || index >= versions.Count) return new(NavKind.None);
        if (index == ReleaseIndex) return new(NavKind.None);
        ReleaseIndex = index;
        _current = versions[index];
        return new(NavKind.Select, FamilyIndex, _current);
    }

    public NavResult GoBack()
    {
        if (Step != SelectorStep.Releases) return new(NavKind.None);
        Step = SelectorStep.Families;
        return new(NavKind.Back, FamilyIndex);
    }

    public NavResult Press(NavKey key) => Step == SelectorStep.Families ? PressFamilies(key) : PressReleases(key);

    private NavResult PressFamilies(NavKey key)
    {
        switch (key)
        {
            case NavKey.Left: return MoveFamily(FamilyIndex - 1);
            case NavKey.Right: return MoveFamily(FamilyIndex + 1);
            case NavKey.Up: return MoveFamily(FamilyIndex - Columns);
            case NavKey.Down: return MoveFamily(FamilyIndex + Columns);
            case NavKey.Home: return MoveFamily(0);
            case NavKey.End: return MoveFamily(_groups.Count - 1);
            case NavKey.Enter: return ChooseFamily(FamilyIndex);
            case NavKey.Escape: return new(NavKind.Close);
            default: return new(NavKind.None);
        }
    }

    // A move that would leave the grid does nothing (it must not jump to the last card).
    private NavResult MoveFamily(int to)
    {
        if (to < 0 || to >= _groups.Count || to == FamilyIndex) return new(NavKind.None);
        FamilyIndex = to;
        return new(NavKind.FamilyFocused, to);
    }

    private NavResult PressReleases(NavKey key)
    {
        var last = Family.Versions.Count - 1;
        switch (key)
        {
            case NavKey.Up: return ReleaseIndex < 0 ? new(NavKind.None) : ChooseRelease(ReleaseIndex - 1);
            case NavKey.Down: return ChooseRelease(ReleaseIndex + 1);
            case NavKey.Home: return ChooseRelease(0);
            case NavKey.End: return ChooseRelease(last);
            case NavKey.Enter: return new(NavKind.OpenList, FamilyIndex);
            case NavKey.Left:
            case NavKey.Backspace: return GoBack();
            case NavKey.Escape: return new(NavKind.Close);
            default: return new(NavKind.None);
        }
    }
}
