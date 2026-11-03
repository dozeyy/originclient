namespace OriginLauncher.App.UI.Controls;

// The list-row base models the Friends and Import drawers share. Framework-free (no WPF or Avalonia type), so the Mac
// launcher links this file as it is (src/OriginLauncher.Mac: Shared\RowModels.cs) and its Import page builds the same rows.

/// <summary>Where a row sits in its card group (Theme/Social.xaml: Social.DrawerRow, and the Import drawer rows).</summary>
public enum CardPos { Only, First, Middle, Last }

/// <summary>One line of the drawer's list.</summary>
public abstract class RowVm
{
    /// <summary>Who this row is, stable across syncs (kind plus uuid).</summary>
    public abstract string Key { get; }

    /// <summary>Everything visible about the row: changes whenever the row must be redrawn.</summary>
    public virtual string Signature => Body;

    protected abstract string Body { get; }
}

/// <summary>A row drawn inside a card group; <see cref="Pos"/> is set when the group is assembled.</summary>
public abstract class CardRowVm : RowVm
{
    public CardPos Pos { get; set; } = CardPos.Only;

    public override string Signature => $"{Body}|{Pos}";
}

/// <summary>A one-line note inside a card ("No friends are online right now").</summary>
public sealed class HintVm : CardRowVm
{
    public required string Id { get; init; }
    public required string Text { get; init; }
    public override string Key => $"H|{Id}";
    protected override string Body => Text;
}
