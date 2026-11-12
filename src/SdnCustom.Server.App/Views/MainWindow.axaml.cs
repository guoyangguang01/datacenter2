using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Avalonia.Interactivity;
using Avalonia.Threading;
using SdnCustom.Server.App.ViewModels;

namespace SdnCustom.Server.App.Views;

public sealed partial class MainWindow : Window
{
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };

    public MainWindow()
    {
        InitializeComponent();
        _timer.Tick += async (_, _) =>
        {
            if (DataContext is MainWindowViewModel viewModel)
            {
                await viewModel.RefreshAsync();
                UpdateChart(viewModel);
            }
        };
        Opened += (_, _) => _timer.Start();
        Closed += (_, _) => _timer.Stop();
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private void OpenClientManagement_OnClick(object? sender, RoutedEventArgs e)
    {
        if (App.AdminFacade is not null)
        {
            new ClientManagementWindow
            {
                DataContext = new ClientManagementViewModel(App.AdminFacade)
            }.Show(this);
        }
    }

    private void OpenSystemSettings_OnClick(object? sender, RoutedEventArgs e)
    {
        new SystemSettingsWindow().Show(this);
    }

    private void UpdateChart(MainWindowViewModel viewModel)
    {
        var values = viewModel.Points
            .Take(30)
            .Select(point => point.Value)
            .Where(value => value is byte or sbyte or short or ushort or int or uint or long or ulong or float or double or decimal)
            .Select(value => Convert.ToDouble(value))
            .ToArray();
        if (values.Length == 0)
        {
            return;
        }

        RealtimeChart.Plot.Clear();
        RealtimeChart.Plot.Add.Scatter(
            Enumerable.Range(0, values.Length).Select(index => (double)index).ToArray(),
            values);
        RealtimeChart.Plot.Title("实时测点值");
        RealtimeChart.Refresh();
    }
}
