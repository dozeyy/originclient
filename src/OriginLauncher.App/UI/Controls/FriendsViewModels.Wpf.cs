using System.Windows;

namespace OriginLauncher.App.UI.Controls;

// The WPF-typed members of the chat line (FriendsPanel.xaml binds them). Kept apart so FriendsViewModels.cs stays
// framework-free and the Mac launcher links it unchanged.
public sealed partial class ChatLineVm
{
    public Thickness Gap => new(0, GapTop, 0, 0);
    public Visibility HeaderVisibility => StartsGroup ? Visibility.Visible : Visibility.Collapsed;
}
