using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;

namespace SdnCustom.Server.App.Views;

public sealed partial class SystemSettingsWindow : Window
{
    public SystemSettingsWindow()
    {
        InitializeComponent();
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private void Close_OnClick(object? sender, RoutedEventArgs e) => Close();
}
