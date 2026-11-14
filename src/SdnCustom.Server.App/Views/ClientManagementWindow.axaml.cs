using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Markup.Xaml;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using SdnCustom.Server.App.ViewModels;

namespace SdnCustom.Server.App.Views;

public sealed partial class ClientManagementWindow : Window
{
    private readonly DispatcherTimer _lockRefreshTimer = new() { Interval = TimeSpan.FromSeconds(2) };

    public ClientManagementWindow()
    {
        InitializeComponent();
        Opened += async (_, _) =>
        {
            if (DataContext is ClientManagementViewModel viewModel)
            {
                try
                {
                    await viewModel.InitializeAsync();
                }
                catch (Exception exception)
                {
                    viewModel.StatusText = $"加载失败：{exception.Message}";
                }
            }
        };
        _lockRefreshTimer.Tick += async (_, _) =>
        {
            if (DataContext is ClientManagementViewModel viewModel)
            {
                try
                {
                    await viewModel.RefreshRunStateAsync();
                }
                catch (Exception exception)
                {
                    viewModel.StatusText = $"状态刷新失败：{exception.Message}";
                }
            }
        };
        Opened += (_, _) => _lockRefreshTimer.Start();
        Closed += (_, _) => _lockRefreshTimer.Stop();
    }

    private void InitializeComponent() => AvaloniaXamlLoader.Load(this);

    private async void ExportOutputs_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is ClientManagementViewModel viewModel)
        {
            await ExportAsync(viewModel, outputOnly: true, "output-points.csv");
        }
    }

    private async void ExportAll_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is ClientManagementViewModel viewModel)
        {
            await ExportAsync(viewModel, outputOnly: false, "points.csv");
        }
    }

    private async Task ExportAsync(
        ClientManagementViewModel viewModel,
        bool outputOnly,
        string suggestedName)
    {
        try
        {
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                Title = outputOnly ? "导出所有输出测点" : "导出点表",
                SuggestedFileName = suggestedName,
                FileTypeChoices = [new FilePickerFileType("CSV") { Patterns = ["*.csv"] }]
            });
            if (file is null)
            {
                return;
            }

            await using var stream = await file.OpenWriteAsync();
            await viewModel.ExportCsvAsync(stream, outputOnly);
        }
        catch (Exception exception)
        {
            viewModel.StatusText = $"导出失败：{exception.Message}";
        }
    }

    private async void Import_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not ClientManagementViewModel viewModel)
        {
            return;
        }

        try
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "导入点表",
                AllowMultiple = false,
                FileTypeFilter = [new FilePickerFileType("CSV") { Patterns = ["*.csv"] }]
            });
            if (files.Count == 0)
            {
                return;
            }

            await using var stream = await files[0].OpenReadAsync();
            await viewModel.ImportCsvAsync(stream);
        }
        catch (Exception exception)
        {
            viewModel.StatusText = $"导入失败：{exception.Message}";
        }
    }

    private async void ImportGasMaster_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not ClientManagementViewModel viewModel)
        {
            return;
        }

        try
        {
            var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
            {
                Title = "导入燃气总表",
                AllowMultiple = false,
                FileTypeFilter = [new FilePickerFileType("CSV") { Patterns = ["*.csv"] }]
            });
            if (files.Count == 0)
            {
                return;
            }

            await using var stream = await files[0].OpenReadAsync();
            await viewModel.ImportGasMasterAsync(stream);
        }
        catch (Exception exception)
        {
            viewModel.StatusText = $"燃气总表导入失败：{exception.Message}";
        }
    }

    private async void ExportGasMaster_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not ClientManagementViewModel viewModel)
        {
            return;
        }

        try
        {
            var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
            {
                Title = "导出燃气总表",
                SuggestedFileName = $"{viewModel.SelectedSystem?.SystemCode ?? "gas"}-master.csv",
                FileTypeChoices = [new FilePickerFileType("CSV") { Patterns = ["*.csv"] }]
            });
            if (file is null)
            {
                return;
            }

            await using var stream = await file.OpenWriteAsync();
            await viewModel.ExportGasMasterAsync(stream);
        }
        catch (Exception exception)
        {
            viewModel.StatusText = $"燃气总表导出失败：{exception.Message}";
        }
    }

    private async void DeleteClient_OnClick(object? sender, RoutedEventArgs e)
    {
        if (DataContext is not ClientManagementViewModel viewModel || viewModel.SelectedClient is null)
        {
            return;
        }

        try
        {
            var confirmation = new ConfirmationWindow(
                $"确定删除客户端“{viewModel.SelectedClient.ClientName} ({viewModel.SelectedClient.ClientCode})”吗？\n" +
                "该客户端的最新值和点表配置会同时删除，此操作不可撤销。",
                "删除");
            if (await confirmation.ShowDialog<bool>(this))
            {
                await viewModel.DeleteSelectedClientAsync();
            }
        }
        catch (Exception exception)
        {
            viewModel.StatusText = $"删除失败：{exception.Message}";
        }
    }
}
