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
    [ObservableProperty] private int _centerToUserFrequencyMilliseconds = 200;
    [ObservableProperty] private int _userToCenterFrequencyMilliseconds = 200;
    [ObservableProperty] private bool _allowCenterToUserMissingItems = true;
    [ObservableProperty] private bool _allowUserToCenterMissingItems = true;
    [ObservableProperty] private bool _sendCenterToUserOnChange;
    [ObservableProperty] private bool _sendUserToCenterOnChange;
    [ObservableProperty] private string _statusText = "未连接服务端";
    [ObservableProperty] private string _serverConnectionButtonText = "连接服务端";
    [ObservableProperty] private int _outputPointCount;
    [ObservableProperty] private int _inputPointCount;
    [ObservableProperty] private string _uploadLatencyText = "--ms";
    [ObservableProperty] private string _downloadLatencyText = "--ms";
    [ObservableProperty] private string _operationMessage = string.Empty;
    [ObservableProperty] private string _configPath = string.Empty;

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
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task SaveAsync()
    {
        try
        {
            await _configStore.SaveAsync(BuildConfiguration());
            OperationMessage = $"配置已保存：{ConfigPath}";
        }
        catch (Exception exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task ConnectServerAsync()
    {
        try
        {
            await _configStore.SaveAsync(BuildConfiguration());
            await _supervisor.StartAsync();
            ServerConnectionButtonText = "断开";
            StatusText = "正在连接服务端...";
            OperationMessage = "客户端监督器已启动。";
        }
        catch (Exception exception)
        {
            StatusText = "连接失败";
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task DisconnectServerAsync()
    {
        await _supervisor.StopAsync();
        ServerConnectionButtonText = "连接服务端";
        StatusText = "已断开服务端";
    }

    [RelayCommand]
    private async Task ToggleServerAsync()
    {
        if (_supervisor.IsServerConnected)
        {
            await DisconnectServerAsync();
        }
        else
        {
            await ConnectServerAsync();
        }
    }

    [RelayCommand]
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
    }

    [RelayCommand]
    private Task StopCommunicationAsync() => _supervisor.StopCommunicationAsync();

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
        CenterToUserFrequencyMilliseconds = CenterToUserFrequencyMilliseconds,
        UserToCenterFrequencyMilliseconds = UserToCenterFrequencyMilliseconds,
        AllowCenterToUserMissingItems = AllowCenterToUserMissingItems,
        AllowUserToCenterMissingItems = AllowUserToCenterMissingItems,
        SendCenterToUserOnChange = SendCenterToUserOnChange,
        SendUserToCenterOnChange = SendUserToCenterOnChange,
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
        CenterToUserFrequencyMilliseconds = configuration.CenterToUserFrequencyMilliseconds;
        UserToCenterFrequencyMilliseconds = configuration.UserToCenterFrequencyMilliseconds;
        AllowCenterToUserMissingItems = configuration.AllowCenterToUserMissingItems;
        AllowUserToCenterMissingItems = configuration.AllowUserToCenterMissingItems;
        SendCenterToUserOnChange = configuration.SendCenterToUserOnChange;
        SendUserToCenterOnChange = configuration.SendUserToCenterOnChange;
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
            ServerConnectionButtonText = _supervisor.IsServerConnected ? "断开" : "连接服务端";
            if (runtimeEvent.LatestValues is null)
            {
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

            UploadLatencyText = "0ms";
            DownloadLatencyText = "0ms";
            RebuildPointRows();
        });
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
            if (point.Direction == PointDirection.Output)
            {
                OutputPoints.Add(row);
            }
            else
            {
                InputPoints.Add(row);
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
