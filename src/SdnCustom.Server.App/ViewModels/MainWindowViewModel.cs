using System.Collections.ObjectModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SdnCustom.Server.Admin;
using SdnCustom.Server.History;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Server.App.ViewModels;

public sealed partial class MainWindowViewModel : ObservableObject
{
    private readonly IAdminFacade _admin;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private CancellationTokenSource? _simulationCancellation;
    private DateTimeOffset? _logViewClearedAt;
    private string? _lastSelectedClientCode;
    private string? _lastSelectedPointId;

    [ObservableProperty] private ClientRowViewModel? _selectedClient;
    [ObservableProperty] private PointRowViewModel? _selectedPoint;
    [ObservableProperty] private string _statusText = "正在读取系统状态...";
    [ObservableProperty] private int _onlineClients;
    [ObservableProperty] private int _totalPoints;
    [ObservableProperty] private string _newClientCode = string.Empty;
    [ObservableProperty] private string _newClientName = string.Empty;
    [ObservableProperty] private string _writeValue = string.Empty;
    [ObservableProperty] private string _operationMessage = string.Empty;
    [ObservableProperty] private string _historyFrom = DateTime.Now.AddHours(-1).ToString("yyyy-MM-dd HH:mm:ss");
    [ObservableProperty] private string _historyTo = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
    [ObservableProperty] private int _historyLimit = 1000;
    [ObservableProperty] private int _simulationIntervalMilliseconds = 200;
    [ObservableProperty] private bool _isSimulationRunning;
    [ObservableProperty] private bool _isHistoryChartActive;
    [ObservableProperty] private bool _isHistoryQuerying;

    public ObservableCollection<ClientRowViewModel> Clients { get; } = [];
    public ObservableCollection<PointRowViewModel> Points { get; } = [];
    public ObservableCollection<PointRowViewModel> OutputPoints { get; } = [];
    public ObservableCollection<PointRowViewModel> InputPoints { get; } = [];
    public ObservableCollection<ServerLogEntry> Logs { get; } = [];
    public ObservableCollection<HistoryPoint> History { get; } = [];
    public string SimulationButtonText => IsSimulationRunning ? "停止模拟" : "开始模拟";
    public bool HasOnlineClients => OnlineClients > 0;
    public bool HasNoOnlineClients => !HasOnlineClients;
    public event EventHandler? HistoryQueryCompleted;

    public MainWindowViewModel(IAdminFacade admin)
    {
        _admin = admin;
    }

    partial void OnSelectedClientChanged(ClientRowViewModel? value)
    {
        var clientChanged = !string.Equals(
            _lastSelectedClientCode,
            value?.ClientCode,
            StringComparison.Ordinal);
        _lastSelectedClientCode = value?.ClientCode;
        if (clientChanged)
        {
            StopSimulation();
            IsHistoryChartActive = false;
        }
        RefreshPoints();
        NotifyCommandStates();
    }

    partial void OnSelectedPointChanged(PointRowViewModel? value)
    {
        var pointChanged = !string.Equals(
            _lastSelectedPointId,
            value?.PointId,
            StringComparison.Ordinal);
        _lastSelectedPointId = value?.PointId;
        if (pointChanged)
        {
            IsHistoryChartActive = false;
        }
        NotifyCommandStates();
    }

    partial void OnHistoryFromChanged(string value) => QueryHistoryCommand.NotifyCanExecuteChanged();
    partial void OnHistoryToChanged(string value) => QueryHistoryCommand.NotifyCanExecuteChanged();
    partial void OnWriteValueChanged(string value) => NotifyCommandStates();

    partial void OnIsSimulationRunningChanged(bool value)
    {
        OnPropertyChanged(nameof(SimulationButtonText));
        ToggleSimulationCommand.NotifyCanExecuteChanged();
        WritePointCommand.NotifyCanExecuteChanged();
    }

    partial void OnIsHistoryQueryingChanged(bool value) =>
        QueryHistoryCommand.NotifyCanExecuteChanged();

    partial void OnOnlineClientsChanged(int value)
    {
        OnPropertyChanged(nameof(HasOnlineClients));
        OnPropertyChanged(nameof(HasNoOnlineClients));
    }

    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        if (!await _refreshGate.WaitAsync(0, cancellationToken))
        {
            return;
        }

        try
        {
            var selectedCode = SelectedClient?.ClientCode;
            var selectedPointId = SelectedPoint?.PointId;
            var snapshot = await _admin.GetSnapshotAsync(cancellationToken);
            Clients.Clear();
            foreach (var client in snapshot.Clients)
            {
                Clients.Add(new ClientRowViewModel(client));
            }

            SelectedClient = Clients.FirstOrDefault(client => client.ClientCode == selectedCode) ?? Clients.FirstOrDefault();
            RefreshPoints();
            SelectedPoint = Points.FirstOrDefault(point => point.PointId == selectedPointId) ?? Points.FirstOrDefault();

            Logs.Clear();
            foreach (var entry in (await _admin.GetLogsAsync(300, cancellationToken))
                         .Where(entry => _logViewClearedAt is null || entry.Timestamp >= _logViewClearedAt)
                         .Reverse())
            {
                Logs.Add(entry);
            }

            OnlineClients = snapshot.OnlineClients;
            TotalPoints = snapshot.TotalPoints;
            StatusText = $"系统监视器：{OnlineClients} 个客户端在线 / {TotalPoints} 个测点";
            NotifyCommandStates();
        }
        catch (Exception exception)
        {
            StatusText = $"刷新失败：{exception.Message}";
        }
        finally
        {
            _refreshGate.Release();
        }
    }

    [RelayCommand]
    private Task RefreshNowAsync() => RefreshAsync();

    [RelayCommand(CanExecute = nameof(CanCreateClient))]
    private async Task CreateClientAsync()
    {
        try
        {
            await _admin.CreateClientAsync(NewClientCode, NewClientName);
            OperationMessage = $"已登记客户端 {NewClientCode}。";
            NewClientCode = string.Empty;
            NewClientName = string.Empty;
            await RefreshAsync();
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    private bool CanCreateClient() =>
        !string.IsNullOrWhiteSpace(NewClientCode) && !string.IsNullOrWhiteSpace(NewClientName);

    partial void OnNewClientCodeChanged(string value) => CreateClientCommand.NotifyCanExecuteChanged();
    partial void OnNewClientNameChanged(string value) => CreateClientCommand.NotifyCanExecuteChanged();

    [RelayCommand(CanExecute = nameof(CanStartClient))]
    private async Task StartClientAsync()
    {
        if (SelectedClient is null) return;
        try
        {
            await _admin.StartClientAsync(SelectedClient.ClientCode);
            OperationMessage = $"已发送启动命令：{SelectedClient.ClientCode}";
            await RefreshAsync();
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    private bool CanStartClient() => SelectedClient is { Online: true, DesiredRunning: false };

    [RelayCommand(CanExecute = nameof(CanStopClient))]
    private async Task StopClientAsync()
    {
        if (SelectedClient is null) return;
        try
        {
            await _admin.StopClientAsync(SelectedClient.ClientCode);
            OperationMessage = $"已发送停止命令：{SelectedClient.ClientCode}";
            await RefreshAsync();
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    private bool CanStopClient() => SelectedClient is { DesiredRunning: true } or { Online: true };

    [RelayCommand(CanExecute = nameof(CanWritePoint))]
    private async Task WritePointAsync()
    {
        if (SelectedClient is null || SelectedPoint is null) return;
        try
        {
            await WriteSelectedPointOnceAsync(SelectedClient.ClientCode, SelectedPoint, WriteValue);
            OperationMessage = $"已发送写命令：{SelectedPoint.PointId} = {WriteValue}";
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    private bool CanWritePoint() =>
        !IsSimulationRunning &&
        SelectedClient is { Online: true } &&
        SelectedPoint is { Direction: PointDirection.Input } &&
        !string.IsNullOrWhiteSpace(WriteValue);

    [RelayCommand(CanExecute = nameof(CanToggleSimulation))]
    private void ToggleSimulation()
    {
        if (IsSimulationRunning)
        {
            StopSimulation();
            return;
        }

        if (SelectedClient is null || SelectedPoint is null)
        {
            return;
        }

        _simulationCancellation = new CancellationTokenSource();
        IsSimulationRunning = true;
        OperationMessage = $"已开始模拟 {SelectedPoint.PointId}，周期 {SimulationIntervalMilliseconds}ms。";
        _ = RunSimulationAsync(
            SelectedClient.ClientCode,
            SelectedPoint,
            WriteValue,
            _simulationCancellation.Token);
    }

    private bool CanToggleSimulation() =>
        IsSimulationRunning ||
        (SelectedClient is { Online: true } &&
         SelectedPoint is { Direction: PointDirection.Input } &&
         !string.IsNullOrWhiteSpace(WriteValue));

    [RelayCommand(CanExecute = nameof(CanQueryHistory))]
    private async Task QueryHistoryAsync()
    {
        if (SelectedClient is null || SelectedPoint is null) return;
        IsHistoryQuerying = true;
        OperationMessage = "正在查询历史数据...";
        try
        {
            var from = DateTimeOffset.Parse(HistoryFrom);
            var to = DateTimeOffset.Parse(HistoryTo);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            var records = await _admin.QueryHistoryAsync(
                SelectedClient.ClientCode,
                SelectedPoint.PointId,
                from,
                to,
                Math.Clamp(HistoryLimit, 1, 10000),
                timeout.Token);
            History.Clear();
            foreach (var record in records)
            {
                History.Add(record);
            }

            IsHistoryChartActive = true;
            OperationMessage = $"查询到 {records.Count} 条历史记录。";
            HistoryQueryCompleted?.Invoke(this, EventArgs.Empty);
        }
        catch (OperationCanceledException)
        {
            OperationMessage = "历史查询超时。请检查 InfluxDB 是否已启动并可访问。";
        }
        catch (Exception exception)
        {
            OperationMessage = $"历史查询失败：{exception.Message}";
        }
        finally
        {
            IsHistoryQuerying = false;
        }
    }

    private bool CanQueryHistory() =>
        SelectedClient is not null &&
        SelectedPoint is not null &&
        !IsHistoryQuerying &&
        DateTimeOffset.TryParse(HistoryFrom, out var from) &&
        DateTimeOffset.TryParse(HistoryTo, out var to) &&
        from < to;

    [RelayCommand]
    private void ResetHistoryFilter()
    {
        HistoryFrom = DateTime.Now.AddHours(-1).ToString("yyyy-MM-dd HH:mm:ss");
        HistoryTo = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss");
        HistoryLimit = 1000;
        History.Clear();
        IsHistoryChartActive = false;
        OperationMessage = "历史查询条件已清除。";
    }

    [RelayCommand]
    private void ClearLogs()
    {
        _logViewClearedAt = DateTimeOffset.Now;
        Logs.Clear();
        OperationMessage = "日志视图已清空；服务端历史日志仍保留。";
    }

    private async Task RunSimulationAsync(
        string clientCode,
        PointRowViewModel point,
        string rawValue,
        CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await WriteSelectedPointOnceAsync(clientCode, point, rawValue, cancellationToken);
                var timestamp = DateTime.Now.ToString("HH:mm:ss");
                OperationMessage = $"{timestamp} 已模拟写入 {point.PointId} = {rawValue}";
                await Task.Delay(
                    Math.Clamp(SimulationIntervalMilliseconds, 50, 60000),
                    cancellationToken);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception exception)
        {
            OperationMessage = $"模拟已停止：{exception.Message}";
        }
        finally
        {
            if (_simulationCancellation?.Token == cancellationToken)
            {
                _simulationCancellation.Dispose();
                _simulationCancellation = null;
                IsSimulationRunning = false;
            }
        }
    }

    private void StopSimulation()
    {
        if (!IsSimulationRunning)
        {
            return;
        }

        _simulationCancellation?.Cancel();
        IsSimulationRunning = false;
        OperationMessage = "数据模拟已停止。";
    }

    private async Task WriteSelectedPointOnceAsync(
        string clientCode,
        PointRowViewModel point,
        string rawValue,
        CancellationToken cancellationToken = default)
    {
        if (!TryConvertWriteValue(rawValue, point.DataType, out var value))
        {
            throw new InvalidOperationException($"“{rawValue}”不是有效的 {point.DataType} 值。");
        }

        await _admin.WritePointAsync(clientCode, point.PointId, value, cancellationToken);
    }

    private static bool TryConvertWriteValue(
        string text,
        PointDataType dataType,
        out object? value)
    {
        var input = text.Trim();
        value = null;
        switch (dataType)
        {
            case PointDataType.Bool when input is "1" or "是" or "开":
                value = true;
                return true;
            case PointDataType.Bool when input is "0" or "否" or "关":
                value = false;
                return true;
            case PointDataType.Bool when bool.TryParse(input, out var boolean):
                value = boolean;
                return true;
            case PointDataType.Int16 when short.TryParse(input, NumberStyles.Integer, CultureInfo.InvariantCulture, out var int16):
                value = int16;
                return true;
            case PointDataType.Int32 when int.TryParse(input, NumberStyles.Integer, CultureInfo.InvariantCulture, out var int32):
                value = int32;
                return true;
            case PointDataType.Float32 when float.TryParse(input, NumberStyles.Float, CultureInfo.InvariantCulture, out var float32):
                value = float32;
                return true;
            case PointDataType.Float64 when double.TryParse(input, NumberStyles.Float, CultureInfo.InvariantCulture, out var float64):
                value = float64;
                return true;
            case PointDataType.String:
                value = text;
                return true;
            default:
                return false;
        }
    }

    private void NotifyCommandStates()
    {
        StartClientCommand.NotifyCanExecuteChanged();
        StopClientCommand.NotifyCanExecuteChanged();
        WritePointCommand.NotifyCanExecuteChanged();
        ToggleSimulationCommand.NotifyCanExecuteChanged();
        QueryHistoryCommand.NotifyCanExecuteChanged();
    }

    private void RefreshPoints()
    {
        Points.Clear();
        OutputPoints.Clear();
        InputPoints.Clear();
        if (SelectedClient is null)
        {
            return;
        }

        foreach (var point in SelectedClient.Points)
        {
            var value = SelectedClient.LatestValues.FirstOrDefault(candidate => candidate.PointId == point.PointId);
            var row = new PointRowViewModel(
                SelectedClient.ClientName,
                SelectedClient.ClientCode,
                point.PointId,
                point.PointName,
                point.Direction,
                point.DataType,
                point.Unit,
                point.Address,
                point.Group,
                value?.Value,
                value?.Quality,
                value is null ? null : UnixTime.FromNanoseconds(value.TimestampUnixNanoseconds));
            Points.Add(row);
            if (point.Direction == PointDirection.Output)
            {
                OutputPoints.Add(row);
            }
            else
            {
                InputPoints.Add(row);
            }
        }
    }
}

public sealed class ClientRowViewModel(AdminClientView client)
{
    public string ClientCode => client.ClientCode;
    public string ClientName => client.ClientName;
    public bool Online => client.Online;
    public bool DesiredRunning => client.DesiredRunning;
    public string OnlineText => client.Online ? "在线" : "离线";
    public string RunningText => client.DesiredRunning ? "期望运行" : "已停止";
    public string Protocol => client.Protocol.ToString();
    public string Endpoint => client.Endpoint;
    public string DisplayName => $"{client.ClientName}  ({client.ClientCode})";
    public IReadOnlyList<PointDefinition> Points => client.Points;
    public IReadOnlyList<AdminPointValue> LatestValues => client.LatestValues;
}

public sealed record PointRowViewModel(
    string ClientName,
    string ClientCode,
    string PointId,
    string PointName,
    PointDirection Direction,
    PointDataType DataType,
    string Unit,
    string Address,
    string Group,
    object? Value,
    PointQuality? Quality,
    DateTimeOffset? Timestamp)
{
    public string ValueText => Value?.ToString() ?? "--";
    public string QualityText => Quality?.ToString() ?? "--";
    public string TimestampText => Timestamp?.ToLocalTime().ToString("HH:mm:ss.fff") ?? "--";
}
