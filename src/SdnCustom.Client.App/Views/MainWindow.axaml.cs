using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using SdnCustom.Client.App.ViewModels;

namespace SdnCustom.Client.App.Views;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        Opened += async (_, _) =>
        {
            if (DataContext is ClientViewModel viewModel)
            {
                await viewModel.InitializeAsync();
            }
        };
        Closed += async (_, _) =>
        {
            if (DataContext is ClientViewModel viewModel)
            {
                await viewModel.DisposeAsync();
            }
        };
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private void OpenDetails_OnClick(object? sender, RoutedEventArgs e)
    {
        new ClientDetailsWindow { DataContext = DataContext }.Show(this);
    }
}
