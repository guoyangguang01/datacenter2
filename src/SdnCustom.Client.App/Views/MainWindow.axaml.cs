using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using SdnCustom.Client.App.ViewModels;

namespace SdnCustom.Client.App.Views;

public sealed partial class MainWindow : Window
{
    private ClientDetailsWindow? _detailsWindow;

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
        if (_detailsWindow is { } existing)
        {
            if (existing.WindowState == WindowState.Minimized)
            {
                existing.WindowState = WindowState.Normal;
            }
            existing.Activate();
            return;
        }

        var window = new ClientDetailsWindow { DataContext = DataContext };
        window.Closed += (_, _) =>
        {
            if (ReferenceEquals(_detailsWindow, window))
            {
                _detailsWindow = null;
            }
        };
        _detailsWindow = window;
        window.Show(this);
    }
}
