using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;

namespace SdnCustom.Client.App.Views;

public sealed partial class ClientDetailsWindow : Window
{
    public ClientDetailsWindow()
    {
        InitializeComponent();
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private void Close_OnClick(object? sender, RoutedEventArgs e) => Close();
}
