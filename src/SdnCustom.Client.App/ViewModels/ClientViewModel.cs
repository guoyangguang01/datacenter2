using System.Collections.ObjectModel;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SdnCustom.Client.Core.Configuration;
using SdnCustom.Client.Core.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Client.App.ViewModels;

public sealed partial class ClientViewModel : ObservableObject, IAsyncDisposable
{
    private readonly IClientConfigStore _configStore;
    private readonly AgentSupervisor _supervisor;
    private readonly Dictionary<string, PointDefinition> _pointLookup = new(StringComparer.Ordinal);
    private readonly Dictionary<string, PointValue> _latestByPointId = new(StringComparer.Ordinal);
    private List<PointDefinition> _configuredPoints = [];

    [ObservableProperty] private string _clientCode = "CLIENT-001";
    [ObservableProperty] private string _clientName = "单连接客户端";
    [ObservableProperty] private string _serverHost = "127.0.0.1";
    [ObservableProperty] private int _serverPort = 7443;
    [ObservableProperty] private bool _useTls = true;
    [ObservableProperty] private bool _allowUntrustedServerCertificate = true;
    [ObservableProperty] private string _serverCertificateThumbprint = string.Empty;
    [ObservableProperty] private ClientProtocol _protocol = ClientProtocol.CustomTcp;
    [ObservableProperty] private string _deviceHost = "127.0.0.1";
    [ObservableProperty] private int _devicePort = 9002;
    [ObservableProperty] private string _opcUaEndpoint = "opc.tcp://127.0.0.1:4840";
    [ObservableProperty] private int _timeoutMilliseconds = 5000;
    [ObservableProperty] private bool _autoStart;
    [ObservableProperty] private int _userToCenterFrequencyMilliseconds = 200;
    [ObservableProperty] private string _statusText = "未连接服务端";
    [ObservableProperty] private string _serverConnectionButtonText = "连接服务端";
    [ObservableProperty] private int _outputPointCount;
    [ObservableProperty] private int _inputPointCount;
    [ObservableProperty] private string _operationMessage = string.Empty;
    [ObservableProperty] private string _configPath = string.Empty;
    [ObservableProperty] private bool _isServerConnected;
    [ObservableProperty] private bool _isDeviceRunning;
    [ObservableProperty] private bool _isSupervisorRunning;
    public bool IsServerDisconnected => !IsServerConnected;
    public bool IsConnectionConfigurationEnabled => !IsSupervisorRunning;

    public ObservableCollection<LatestValueRow> LatestValues { get; } = [];
    public ObservableCollection<ClientPointRow> OutputPoints { get; } = [];
    public ObservableCollection<ClientPointRow> InputPoints { get; } = [];
    public ObservableCollection<ClientLogEntry> Logs { get; } = [];
    public IReadOnlyList<ClientProtocol> ProtocolOptions { get; } = Enum.GetValues<ClientProtocol>();

    public ClientViewModel()
    {
        _configStore = new ClientConfigStore();
        _supervisor = new AgentSupervisor(_configStore);
        ConfigPath = _configStore.ConfigPath;
        _supervisor.RuntimeEvent += OnRuntimeEvent;
        _supervisor.LogReceived += OnLogReceived;
        _supervisor.ConfigurationChanged += OnConfigurationChanged;
    }

    public async Task InitializeAsync()
    {
        try
        {
            var configuration = await _configStore.LoadAsync();
            ApplyConfiguration(configuration);
            OperationMessage = File.Exists(ConfigPath) ? "配置已加载。" : "尚未保存配置，请完成通讯设置并保存。";
            RefreshSupervisorState();
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand(CanExecute = nameof(CanSaveConfiguration))]
    private Task SaveAsync() => SaveConfigurationAsync();

    private bool CanSaveConfiguration() => !IsSupervisorRunning;

    public async Task<bool> SaveConfigurationAsync()
    {
        if (_supervisor.IsRunning)
        {
            OperationMessage = "配置正在使用中。请先断开服务端，再保存配置。";
            return false;
        }

        try
        {
            await _configStore.SaveAsync(BuildConfiguration());
            OperationMessage = $"配置已保存：{ConfigPath}";
            return true;
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
            return false;
        }
    }

    [RelayCommand]
    private Task ReloadAsync() => ReloadConfigurationAsync();

    public async Task ReloadConfigurationAsync()
    {
        try
        {
            ApplyConfiguration(await _configStore.LoadAsync());
            OperationMessage = "已重新载入最近保存的配置。";
        }
        catch (Exception exception)
        {
            OperationMessage = $"载入配置失败：{exception.Message}";
        }
    }

    [RelayCommand(CanExecute = nameof(CanConnectServer))]
    private async Task ConnectServerAsync()
    {
        try
        {
            await _configStore.SaveAsync(BuildConfiguration());
            await _supervisor.StartAsync();
            StatusText = "正在连接服务端...";
            OperationMessage = "客户端监督器已启动。";
            RefreshSupervisorState();
        }
        catch (Exception exception)
        {
            StatusText = "连接失败";
            OperationMessage = exception.Message;
        }
    }

    private bool CanConnectServer() => !IsSupervisorRunning;

    [RelayCommand(CanExecute = nameof(CanDisconnectServer))]
    private async Task DisconnectServerAsync()
    {
        await _supervisor.StopAsync();
        StatusText = "已断开服务端";
        RefreshSupervisorState();
    }

    private bool CanDisconnectServer() => IsSupervisorRunning;

    [RelayCommand]
    private async Task ToggleServerAsync()
    {
        if (_supervisor.IsRunning)
        {
            await DisconnectServerAsync();
        }
        else
        {
            await ConnectServerAsync();
        }
    }

    [RelayCommand(CanExecute = nameof(CanStartCommunication))]
    private async Task StartCommunicationAsync()
    {
        try
        {
            await _supervisor.StartCommunicationAsync();
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
        finally
        {
            RefreshSupervisorState();
        }
    }

    [RelayCommand(CanExecute = nameof(CanStopCommunication))]
    private async Task StopCommunicationAsync()
    {
        try
        {
            await _supervisor.StopCommunicationAsync();
            OperationMessage = "设备通讯已停止。";
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
        finally
        {
            RefreshSupervisorState();
        }
    }

    private bool CanStartCommunication() => IsServerConnected && !IsDeviceRunning;
    private bool CanStopCommunication() => IsDeviceRunning;

    [RelayCommand]
    private void ClearLogs()
    {
        Logs.Clear();
        OperationMessage = "运行日志视图已清空。";
    }

    public async ValueTask DisposeAsync()
    {
        _supervisor.RuntimeEvent -= OnRuntimeEvent;
        _supervisor.LogReceived -= OnLogReceived;
        _supervisor.ConfigurationChanged -= OnConfigurationChanged;
        await _supervisor.DisposeAsync();
    }

    private ClientConfiguration BuildConfiguration() => new()
    {
        ClientCode = ClientCode.Trim(),
        ClientName = ClientName.Trim(),
        AutoStart = AutoStart,
        UserToCenterFrequencyMilliseconds = UserToCenterFrequencyMilliseconds,
        Server = new ServerConnectionOptions
        {
            Host = ServerHost.Trim(),
            Port = ServerPort,
            UseTls = UseTls,
            AllowUntrustedServerCertificate = AllowUntrustedServerCertificate,
            ServerCertificateThumbprint = ServerCertificateThumbprint.Trim()
        },
        Device = new DeviceOptions
        {
            Protocol = Protocol,
            Host = DeviceHost.Trim(),
            Port = DevicePort,
            OpcUaEndpoint = OpcUaEndpoint.Trim(),
            TimeoutMilliseconds = TimeoutMilliseconds
        },
        Points = _configuredPoints
    };

    private void ApplyConfiguration(ClientConfiguration configuration)
    {
        ClientCode = configuration.ClientCode;
        ClientName = configuration.ClientName;
        ServerHost = configuration.Server.Host;
        ServerPort = configuration.Server.Port;
        UseTls = configuration.Server.UseTls;
        AllowUntrustedServerCertificate = configuration.Server.AllowUntrustedServerCertificate;
        ServerCertificateThumbprint = configuration.Server.ServerCertificateThumbprint;
        Protocol = configuration.Device.Protocol;
        DeviceHost = configuration.Device.Host;
        DevicePort = configuration.Device.Port;
        OpcUaEndpoint = configuration.Device.OpcUaEndpoint;
        TimeoutMilliseconds = configuration.Device.TimeoutMilliseconds;
        AutoStart = configuration.AutoStart;
        UserToCenterFrequencyMilliseconds = configuration.UserToCenterFrequencyMilliseconds;
        _configuredPoints = configuration.Points.ToList();
        _pointLookup.Clear();
        foreach (var point in _configuredPoints)
        {
            _pointLookup[point.PointId] = point;
        }
        RebuildPointRows();
    }

    private void OnRuntimeEvent(object? sender, ClientRuntimeEvent runtimeEvent)
    {
        Dispatcher.UIThread.Post(() =>
        {
            StatusText = runtimeEvent.Message;
            RefreshSupervisorState();
            if (runtimeEvent.LatestValues is null)
            {
                if (runtimeEvent.State != ClientConnectionState.Connected)
                {
                    _latestByPointId.Clear();
                    RebuildPointRows();
                }
                return;
            }

            foreach (var sample in runtimeEvent.LatestValues)
            {
                _latestByPointId[sample.PointId] = sample.Value;
                var existing = LatestValues.FirstOrDefault(row => row.PointId == sample.PointId);
                if (existing is not null)
                {
                    LatestValues.Remove(existing);
                }
                LatestValues.Insert(0, new LatestValueRow(
                    sample.PointId,
                    _pointLookup.GetValueOrDefault(sample.PointId)?.PointName ?? sample.PointId,
                    sample.Value.Value?.ToString() ?? "--",
                    sample.Value.Quality.ToString(),
                    UnixTime.FromNanoseconds(sample.Value.TimestampUnixNanoseconds).ToLocalTime()));
            }

            RebuildPointRows();
        });
    }

    private void RefreshSupervisorState()
    {
        IsServerConnected = _supervisor.IsServerConnected;
        IsDeviceRunning = _supervisor.IsDeviceRunning;
        IsSupervisorRunning = _supervisor.IsRunning;
        ServerConnectionButtonText = IsServerConnected
            ? "断开"
            : IsSupervisorRunning
                ? "取消连接"
                : "连接服务端";
        ConnectServerCommand.NotifyCanExecuteChanged();
        DisconnectServerCommand.NotifyCanExecuteChanged();
        SaveCommand.NotifyCanExecuteChanged();
        StartCommunicationCommand.NotifyCanExecuteChanged();
        StopCommunicationCommand.NotifyCanExecuteChanged();
    }

    partial void OnIsServerConnectedChanged(bool value)
    {
        OnPropertyChanged(nameof(IsServerDisconnected));
        RefreshCommandStates();
    }
    partial void OnIsDeviceRunningChanged(bool value) => RefreshCommandStates();
    partial void OnIsSupervisorRunningChanged(bool value) =>
        RefreshSupervisorConfigurationState();

    private void RefreshSupervisorConfigurationState()
    {
        OnPropertyChanged(nameof(IsConnectionConfigurationEnabled));
        SaveCommand.NotifyCanExecuteChanged();
    }

    private void RefreshCommandStates()
    {
        StartCommunicationCommand.NotifyCanExecuteChanged();
        StopCommunicationCommand.NotifyCanExecuteChanged();
    }

    private void OnLogReceived(object? sender, ClientLogEntry entry)
    {
        Dispatcher.UIThread.Post(() =>
        {
            Logs.Insert(0, entry);
            while (Logs.Count > 500)
            {
                Logs.RemoveAt(Logs.Count - 1);
            }
        });
    }

    private void OnConfigurationChanged(object? sender, ClientConfiguration configuration)
    {
        Dispatcher.UIThread.Post(() =>
        {
            ApplyConfiguration(configuration);
            OperationMessage = $"已接收服务端点表：{configuration.Points.Count} 个测点。";
        });
    }

    private void RebuildPointRows()
    {
        OutputPoints.Clear();
        InputPoints.Clear();
        foreach (var point in _configuredPoints)
        {
            var value = _latestByPointId.GetValueOrDefault(point.PointId);
            var row = new ClientPointRow(
                point.PointId,
                point.PointName,
                value?.Value?.ToString() ?? "--",
                point.Unit,
                value?.Quality.ToString() ?? "--",
                value is null
                    ? "--"
                    : UnixTime.FromNanoseconds(value.TimestampUnixNanoseconds).ToLocalTime().ToString("HH:mm:ss.fff"));
            if (point.Direction == PointDirection.Input)
            {
                InputPoints.Add(row);
            }
            else
            {
                OutputPoints.Add(row);
            }
        }
        OutputPointCount = OutputPoints.Count;
        InputPointCount = InputPoints.Count;
    }
}

public sealed record LatestValueRow(
    string PointId,
    string PointName,
    string Value,
    string Quality,
    DateTimeOffset Timestamp);

public sealed record ClientPointRow(
    string PointId,
    string PointName,
    string Data,
    string Unit,
    string Quality,
    string Timestamp);
