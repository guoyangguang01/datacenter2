using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using SdnCustom.Client.App.ViewModels;

namespace SdnCustom.Client.App.Views;

public sealed partial class ClientDetailsWindow : Window
{
    public ClientDetailsWindow()
    {
        InitializeComponent();
        Closed += async (_, _) =>
        {
            if (DataContext is ClientViewModel viewModel)
            {
                await viewModel.ReloadConfigurationAsync();
            }
        };
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private async void SaveAndClose_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is ClientViewModel viewModel)
        {
            if (!await viewModel.SaveConfigurationAsync())
            {
                return;
            }
        }
        Close();
    }

    private void Cancel_OnClick(object? sender, RoutedEventArgs e) => Close();
}
