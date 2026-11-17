using System.Collections.ObjectModel;
using Avalonia.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using SdnCustom.Client.Core.Configuration;
using SdnCustom.Client.Core.Discovery;
using SdnCustom.Client.Core.Runtime;
using SdnCustom.Shared.Model;

namespace SdnCustom.Client.App.ViewModels;

public sealed partial class ClientViewModel : ObservableObject, IAsyncDisposable
{
    private readonly IClientConfigStore _configStore;
    private readonly AgentSupervisor _supervisor;
    private readonly IClientDirectoryClient _clientDirectory;
    private readonly Dictionary<(string PointId, PointDirection Direction), PointDefinition> _pointLookup = new();
    private readonly Dictionary<(string PointId, PointDirection Direction), PointValue> _latestByPointId = new();
    private List<PointDefinition> _configuredPoints = [];

    [ObservableProperty] private string _clientCode = "CLIENT-001";
    [ObservableProperty] private string _clientName = "单连接客户端";
    [ObservableProperty] private string _serverHost = "127.0.0.1";
    [ObservableProperty] private int _serverPort = 7443;
    [ObservableProperty] private int _managementPort = 7444;
    [ObservableProperty] private bool _useTls;
    [ObservableProperty] private bool _allowUntrustedServerCertificate = true;
    [ObservableProperty] private string _serverCertificateThumbprint = string.Empty;
    [ObservableProperty] private ClientProtocol _protocol = ClientProtocol.Tcp;
    [ObservableProperty] private string _deviceHost = "127.0.0.1";
    [ObservableProperty] private int _devicePort = 9002;
    [ObservableProperty] private string _opcUaEndpoint = "opc.tcp://127.0.0.1:4840";
    [ObservableProperty] private int _timeoutMilliseconds = 5000;
    [ObservableProperty] private bool _autoStart;
    [ObservableProperty] private int _userToCenterFrequencyMilliseconds = 200;
    [ObservableProperty] private string _statusText = "未连接服务端";
    [ObservableProperty] private string _serverConnectionButtonText = "连接服务器";
    [ObservableProperty] private string _clientConnectionButtonText = "连接客户端";
    [ObservableProperty] private int _outputPointCount;
    [ObservableProperty] private int _inputPointCount;
    [ObservableProperty] private string _operationMessage = string.Empty;
    [ObservableProperty] private string _configPath = string.Empty;
    [ObservableProperty] private bool _isServerConnected;
    [ObservableProperty] private bool _isManagementServerConnected;
    [ObservableProperty] private bool _isDeviceRunning;
    [ObservableProperty] private bool _isSupervisorRunning;
    [ObservableProperty] private bool _isLoadingRegisteredClients;
    [ObservableProperty] private RegisteredClient? _selectedRegisteredClient;
    [ObservableProperty] private ProtocolDisplayOption? _selectedProtocolOption;
    public bool IsServerDisconnected => !IsServerConnected;
    public bool IsServerConfigurationEnabled => !IsManagementServerConnected;
    public bool IsClientSelectionEnabled => IsManagementServerConnected && !IsSupervisorRunning;
    public bool IsExternalServiceConfigurationEnabled => !IsDeviceRunning;
    public bool IsOpcUaServerProtocol => Protocol == ClientProtocol.OpcUaServer;
    public string OpcUaEndpointLabel => Protocol switch
    {
        ClientProtocol.OpcUaServer => "OPC UA 监听 Endpoint",
        ClientProtocol.OpcUaClient => "OPC UA 服务器 Endpoint",
        _ => "OPC UA Endpoint（当前协议不使用）"
    };
    public string ExternalModeDescription => Protocol switch
    {
        ClientProtocol.Tcp =>
            "TCP 服务端模式：当前系统监听，燃油系统用户主动连接；接收 MsgPack datas 作为 INPUT，并按同一协议推送 OUTPUT。",
        ClientProtocol.OpcUaClient =>
            "OPC UA 客户端模式：连接外部 OPC UA 服务器并读写配置的节点。",
        ClientProtocol.OpcUaServer =>
            "OPC UA 服务端模式：启动内置服务器，并按当前客户端点表创建输入/输出节点。",
        _ => string.Empty
    };
    public string ExternalHostLabel => Protocol == ClientProtocol.Tcp
        ? "监听 IP"
        : "外部服务 IP";
    public string ExternalPortLabel => Protocol == ClientProtocol.Tcp
        ? "监听端口"
        : "端口";

    public ObservableCollection<LatestValueRow> LatestValues { get; } = [];
    public ObservableCollection<ClientPointRow> OutputPoints { get; } = [];
    public ObservableCollection<ClientPointRow> InputPoints { get; } = [];
    public ObservableCollection<RegisteredClient> RegisteredClients { get; } = [];
    public ObservableCollection<ClientLogEntry> Logs { get; } = [];
    public IReadOnlyList<ProtocolDisplayOption> ProtocolOptions { get; } =
    [
        new(ClientProtocol.Tcp, "TCP"),
        new(ClientProtocol.OpcUaClient, "OPC UA 客户端"),
        new(ClientProtocol.OpcUaServer, "OPC UA 服务端")
    ];

    public ClientViewModel() : this(new AdminClientDirectoryClient())
    {
    }

    public ClientViewModel(IClientDirectoryClient clientDirectory)
    {
        _clientDirectory = clientDirectory;
        _configStore = new ClientConfigStore();
        _supervisor = new AgentSupervisor(_configStore);
        SelectedProtocolOption = ProtocolOptions[0];
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
            OperationMessage = File.Exists(ConfigPath)
                ? "配置已加载，请先连接服务器。"
                : "尚未保存配置，请先连接服务器。";
            RefreshSupervisorState();
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    public async Task<bool> SaveConfigurationAsync()
    {
        if (_supervisor.IsDeviceRunning)
        {
            OperationMessage = "配置正在使用中。请先停止外部服务通讯，再保存配置。";
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

    [RelayCommand(CanExecute = nameof(CanConnectClient))]
    private async Task ConnectClientAsync()
    {
        try
        {
            if (!await SaveConfigurationAsync())
            {
                return;
            }
            await _supervisor.StartAsync();
            StatusText = $"正在连接客户端 {ClientCode}...";
            OperationMessage = $"正在连接客户端 {ClientCode}。";
            RefreshSupervisorState();
        }
        catch (Exception exception)
        {
            StatusText = "连接失败";
            OperationMessage = exception.Message;
        }
    }

    private bool CanConnectClient() =>
        IsManagementServerConnected &&
        SelectedRegisteredClient is not null &&
        !IsSupervisorRunning;

    [RelayCommand(CanExecute = nameof(CanDisconnectClient))]
    private async Task DisconnectClientAsync()
    {
        await _supervisor.StopAsync();
        StatusText = "已断开客户端";
        OperationMessage = "客户端连接已断开。";
        RefreshSupervisorState();
    }

    private bool CanDisconnectClient() => IsSupervisorRunning;

    [RelayCommand(CanExecute = nameof(CanToggleClient))]
    private async Task ToggleClientAsync()
    {
        if (_supervisor.IsRunning)
        {
            await DisconnectClientAsync();
        }
        else
        {
            await ConnectClientAsync();
        }
    }

    private bool CanToggleClient() => IsSupervisorRunning || CanConnectClient();

    [RelayCommand]
    private async Task ToggleServerAsync()
    {
        if (IsManagementServerConnected)
        {
            if (_supervisor.IsRunning)
            {
                await _supervisor.StopAsync();
            }

            IsManagementServerConnected = false;
            RegisteredClients.Clear();
            SelectedRegisteredClient = null;
            StatusText = "已断开服务器";
            OperationMessage = "服务器连接已断开。";
            RefreshSupervisorState();
            return;
        }

        if (!await SaveConfigurationAsync())
        {
            StatusText = "连接失败";
            return;
        }

        var connected = await LoadRegisteredClientsAsync(reportErrors: true);
        IsManagementServerConnected = connected;
        StatusText = connected ? "服务器已连接，请选择客户端" : "连接失败";
        RefreshSupervisorState();
    }

    [RelayCommand(CanExecute = nameof(CanStartCommunication))]
    private async Task StartCommunicationAsync()
    {
        try
        {
            if (!await SaveConfigurationAsync())
            {
                return;
            }
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
        if (_clientDirectory is IDisposable disposable)
        {
            disposable.Dispose();
        }
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
            ManagementPort = ManagementPort,
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
        ManagementPort = configuration.Server.ManagementPort;
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
            _pointLookup[(point.PointId, point.Direction)] = point;
        }
        RebuildPointRows();
    }

    partial void OnSelectedRegisteredClientChanged(RegisteredClient? value)
    {
        ToggleClientCommand.NotifyCanExecuteChanged();
        if (value is null)
        {
            return;
        }

        ClientCode = value.ClientCode;
        ClientName = value.ClientName;
    }

    private async Task<bool> LoadRegisteredClientsAsync(bool reportErrors = false)
    {
        if (IsSupervisorRunning || IsLoadingRegisteredClients)
        {
            return false;
        }

        IsLoadingRegisteredClients = true;
        try
        {
            var clients = await _clientDirectory.ListAsync(new ServerConnectionOptions
            {
                Host = ServerHost.Trim(),
                Port = ServerPort,
                ManagementPort = ManagementPort,
                UseTls = UseTls,
                AllowUntrustedServerCertificate = AllowUntrustedServerCertificate,
                ServerCertificateThumbprint = ServerCertificateThumbprint.Trim()
            });

            RegisteredClients.Clear();
            foreach (var client in clients.OrderBy(client => client.ClientCode, StringComparer.Ordinal))
            {
                RegisteredClients.Add(client);
            }

            SelectedRegisteredClient = RegisteredClients.FirstOrDefault(client =>
                string.Equals(client.ClientCode, ClientCode, StringComparison.Ordinal));
            if (reportErrors)
            {
                OperationMessage = $"已加载 {RegisteredClients.Count} 个已登记客户端。";
            }
            return true;
        }
        catch (Exception exception)
        {
            if (reportErrors)
            {
                OperationMessage = $"加载客户端列表失败：{exception.Message}";
            }
            return false;
        }
        finally
        {
            IsLoadingRegisteredClients = false;
        }
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
                var direction = string.IsNullOrWhiteSpace(sample.SourcePointId)
                    ? PointDirection.Input
                    : PointDirection.Output;
                _latestByPointId[(sample.PointId, direction)] = sample.Value;
                var existing = LatestValues.FirstOrDefault(row =>
                    row.PointId == sample.PointId &&
                    row.Direction == direction);
                if (existing is not null)
                {
                    LatestValues.Remove(existing);
                }
                LatestValues.Insert(0, new LatestValueRow(
                    sample.PointId,
                    _pointLookup.GetValueOrDefault((sample.PointId, direction))?.PointName ?? sample.PointId,
                    direction,
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
        ServerConnectionButtonText = IsManagementServerConnected
            ? "断开服务器"
            : "连接服务器";
        ClientConnectionButtonText = IsServerConnected
            ? "断开客户端"
            : IsSupervisorRunning
                ? "取消连接"
                : "连接客户端";
        ToggleClientCommand.NotifyCanExecuteChanged();
        DisconnectClientCommand.NotifyCanExecuteChanged();
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

    partial void OnIsManagementServerConnectedChanged(bool value)
    {
        OnPropertyChanged(nameof(IsServerConfigurationEnabled));
        OnPropertyChanged(nameof(IsClientSelectionEnabled));
        ToggleClientCommand.NotifyCanExecuteChanged();
    }

    partial void OnProtocolChanged(ClientProtocol value)
    {
        SelectedProtocolOption = ProtocolOptions.FirstOrDefault(option => option.Value == value);
        OnPropertyChanged(nameof(IsOpcUaServerProtocol));
        OnPropertyChanged(nameof(OpcUaEndpointLabel));
        OnPropertyChanged(nameof(ExternalModeDescription));
        OnPropertyChanged(nameof(ExternalHostLabel));
        OnPropertyChanged(nameof(ExternalPortLabel));
    }

    partial void OnSelectedProtocolOptionChanged(ProtocolDisplayOption? value)
    {
        if (value is not null)
        {
            Protocol = value.Value;
        }
    }

    private void RefreshSupervisorConfigurationState()
    {
        OnPropertyChanged(nameof(IsClientSelectionEnabled));
        ToggleClientCommand.NotifyCanExecuteChanged();
    }

    private void RefreshCommandStates()
    {
        OnPropertyChanged(nameof(IsExternalServiceConfigurationEnabled));
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
            var value = _latestByPointId.GetValueOrDefault((point.PointId, point.Direction));
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

public sealed record ProtocolDisplayOption(ClientProtocol Value, string DisplayName)
{
    public override string ToString() => DisplayName;
}

public sealed record LatestValueRow(
    string PointId,
    string PointName,
    PointDirection Direction,
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
