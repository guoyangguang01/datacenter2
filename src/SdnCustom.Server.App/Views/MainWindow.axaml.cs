using Avalonia.Controls;
using Avalonia.Markup.Xaml;
using Avalonia.Interactivity;
using Avalonia.Threading;
using SdnCustom.Server.App.Controls;
using SdnCustom.Server.App.ViewModels;

namespace SdnCustom.Server.App.Views;

public sealed partial class MainWindow : Window
{
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly TabControl _workspaceTabs;
    private readonly HistoryLineChart _historyChart;
    private readonly Border _logPanel;
    private readonly ListBox _logList;
    private MainWindowViewModel? _viewModel;
    private ClientManagementWindow? _clientManagementWindow;
    private SystemSettingsWindow? _systemSettingsWindow;
    private HelpWindow? _helpWindow;

    public MainWindow()
    {
        InitializeComponent();
        _workspaceTabs = this.FindControl<TabControl>("WorkspaceTabs")
                         ?? throw new InvalidOperationException("WorkspaceTabs control is missing.");
        _historyChart = this.FindControl<HistoryLineChart>("HistoryChart")
                        ?? throw new InvalidOperationException("HistoryChart control is missing.");
        _logPanel = this.FindControl<Border>("LogPanel")
                    ?? throw new InvalidOperationException("LogPanel control is missing.");
        _logList = this.FindControl<ListBox>("LogList")
                   ?? throw new InvalidOperationException("LogList control is missing.");
        _timer.Tick += async (_, _) =>
        {
            if (DataContext is MainWindowViewModel viewModel)
            {
                await viewModel.RefreshAsync();
            }
        };
        DataContextChanged += (_, _) => AttachViewModel(DataContext as MainWindowViewModel);
        Opened += (_, _) => _timer.Start();
        Closed += (_, _) =>
        {
            _timer.Stop();
            AttachViewModel(null);
        };
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private void OpenClientManagement_OnClick(object? sender, RoutedEventArgs e)
    {
        if (ActivateExistingWindow(_clientManagementWindow))
        {
            return;
        }

        if (App.AdminFacade is null)
        {
            return;
        }

        var window = new ClientManagementWindow
        {
            DataContext = new ClientManagementViewModel(App.AdminFacade)
        };
        window.Closed += (_, _) => ClearWindowReference(window, ref _clientManagementWindow);
        _clientManagementWindow = window;
        window.Show(this);
    }

    private void OpenSystemSettings_OnClick(object? sender, RoutedEventArgs e)
    {
        if (ActivateExistingWindow(_systemSettingsWindow))
        {
            return;
        }

        var window = new SystemSettingsWindow(App.AdminFacade);
        window.Closed += (_, _) => ClearWindowReference(window, ref _systemSettingsWindow);
        _systemSettingsWindow = window;
        window.Show(this);
    }

    private void ShowDataView_OnClick(object? sender, RoutedEventArgs e) => _workspaceTabs.SelectedIndex = 0;

    private void ShowSimulation_OnClick(object? sender, RoutedEventArgs e) => _workspaceTabs.SelectedIndex = 1;

    private void ShowHistory_OnClick(object? sender, RoutedEventArgs e) => _workspaceTabs.SelectedIndex = 2;

    private void FocusLogs_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is MainWindowViewModel viewModel)
        {
            _logPanel.BringIntoView();
            if (viewModel.Logs.Count > 0)
            {
                _logList.SelectedIndex = 0;
                _logList.ScrollIntoView(0);
            }
            _logList.Focus();
        }
    }

    private void OpenHelp_OnClick(object? sender, RoutedEventArgs e)
    {
        if (ActivateExistingWindow(_helpWindow))
        {
            return;
        }

        var window = new HelpWindow();
        window.Closed += (_, _) => ClearWindowReference(window, ref _helpWindow);
        _helpWindow = window;
        window.Show(this);
    }

    private static bool ActivateExistingWindow(Window? window)
    {
        if (window is null)
        {
            return false;
        }

        if (window.WindowState == WindowState.Minimized)
        {
            window.WindowState = WindowState.Normal;
        }
        window.Activate();
        return true;
    }

    private static void ClearWindowReference<TWindow>(
        TWindow window,
        ref TWindow? reference)
        where TWindow : Window
    {
        if (ReferenceEquals(reference, window))
        {
            reference = null;
        }
    }

    private void DrawChart_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is MainWindowViewModel viewModel)
        {
            viewModel.IsHistoryChartActive = true;
            DrawHistoryChart(viewModel);
        }
    }

    private void AttachViewModel(MainWindowViewModel? viewModel)
    {
        if (ReferenceEquals(_viewModel, viewModel))
        {
            return;
        }

        if (_viewModel is not null)
        {
            _viewModel.HistoryQueryCompleted -= OnHistoryQueryCompleted;
            _viewModel.HistoryChartResetRequested -= OnHistoryChartResetRequested;
        }

        _viewModel = viewModel;
        if (_viewModel is not null)
        {
            _viewModel.HistoryQueryCompleted += OnHistoryQueryCompleted;
            _viewModel.HistoryChartResetRequested += OnHistoryChartResetRequested;
        }
    }

    private void OnHistoryQueryCompleted(object? sender, EventArgs e)
    {
        if (sender is MainWindowViewModel viewModel)
        {
            DrawHistoryChart(viewModel);
        }
    }

    private void OnHistoryChartResetRequested(object? sender, EventArgs e) =>
        _historyChart.ShowMessage("历史数据", "查询后在此显示图表");

    private void DrawHistoryChart(MainWindowViewModel viewModel)
    {
        if (viewModel.History.Count == 0)
        {
            _historyChart.ShowMessage("历史查询结果为空", "当前时间和测点范围内没有历史数据");
            return;
        }

        var points = viewModel.History
            .Where(point => TryGetFiniteValue(point.Value, out _))
            .ToArray();
        if (points.Length == 0)
        {
            _historyChart.ShowMessage("无法绘制图表", "所选历史数据不是数值类型");
            return;
        }

        try
        {
            var origin = points[0].Timestamp;
            var xs = points.Select(point => (point.Timestamp - origin).TotalSeconds).ToArray();
            var ys = points
                .Select(point => TryGetFiniteValue(point.Value, out var value) ? value : double.NaN)
                .ToArray();
            _historyChart.SetSeries(
                $"{viewModel.SelectedPoint?.PointId ?? "历史数据"}（{origin:yyyy-MM-dd HH:mm:ss} 起）",
                xs,
                ys);
        }
        catch (Exception exception)
        {
            _historyChart.ShowMessage("图表绘制失败", exception.Message);
        }
    }

    private static bool TryGetFiniteValue(object? value, out double result)
    {
        if (value is byte or sbyte or short or ushort or int or uint or long or ulong or float or double or decimal)
        {
            result = Convert.ToDouble(value);
            return double.IsFinite(result);
        }

        result = 0;
        return false;
    }
}
