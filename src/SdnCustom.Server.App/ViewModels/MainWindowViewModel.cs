using System.Collections.ObjectModel;
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

    public ObservableCollection<ClientRowViewModel> Clients { get; } = [];
    public ObservableCollection<PointRowViewModel> Points { get; } = [];
    public ObservableCollection<PointRowViewModel> OutputPoints { get; } = [];
    public ObservableCollection<PointRowViewModel> InputPoints { get; } = [];
    public ObservableCollection<ServerLogEntry> Logs { get; } = [];
    public ObservableCollection<HistoryPoint> History { get; } = [];

    public MainWindowViewModel(IAdminFacade admin)
    {
        _admin = admin;
    }

    partial void OnSelectedClientChanged(ClientRowViewModel? value) => RefreshPoints();

    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
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
            foreach (var entry in (await _admin.GetLogsAsync(300, cancellationToken)).Reverse())
            {
                Logs.Add(entry);
            }

            OnlineClients = snapshot.OnlineClients;
            TotalPoints = snapshot.TotalPoints;
            StatusText = $"系统监视器：{OnlineClients} 个客户端在线 / {TotalPoints} 个测点";
        }
        catch (Exception exception)
        {
            StatusText = $"刷新失败：{exception.Message}";
        }
    }

    [RelayCommand]
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

    [RelayCommand]
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

    [RelayCommand]
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

    [RelayCommand]
    private async Task WritePointAsync()
    {
        if (SelectedClient is null || SelectedPoint is null) return;
        try
        {
            object? value = SelectedPoint.DataType switch
            {
                PointDataType.Bool => bool.Parse(WriteValue),
                PointDataType.Int16 => short.Parse(WriteValue),
                PointDataType.Int32 => int.Parse(WriteValue),
                PointDataType.Float32 => float.Parse(WriteValue),
                PointDataType.Float64 => double.Parse(WriteValue),
                _ => WriteValue
            };
            await _admin.WritePointAsync(SelectedClient.ClientCode, SelectedPoint.PointId, value);
            OperationMessage = $"已发送写命令：{SelectedPoint.PointId} = {WriteValue}";
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task QueryHistoryAsync()
    {
        if (SelectedClient is null || SelectedPoint is null) return;
        try
        {
            var from = DateTimeOffset.Parse(HistoryFrom);
            var to = DateTimeOffset.Parse(HistoryTo);
            var records = await _admin.QueryHistoryAsync(
                SelectedClient.ClientCode,
                SelectedPoint.PointId,
                from,
                to,
                1000);
            History.Clear();
            foreach (var record in records)
            {
                History.Add(record);
            }

            OperationMessage = $"查询到 {records.Count} 条历史记录。";
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
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
    public string OnlineText => client.Online ? "在线" : "离线";
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
