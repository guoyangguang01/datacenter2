using System.Collections.Concurrent;
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using Grpc.Core;
using Grpc.Net.Client;
using SdnCustom.Client.Core.Configuration;
using SdnCustom.Contracts;
using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Protocols;
using SdnCustom.Shared.Model;
using SharedClientConnectionState = SdnCustom.Shared.Model.ClientConnectionState;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;
using SharedPointDirection = SdnCustom.Shared.Model.PointDirection;
using SharedPointQuality = SdnCustom.Shared.Model.PointQuality;
using SharedPointSample = SdnCustom.Shared.Model.PointSample;
using SharedPointValue = SdnCustom.Shared.Model.PointValue;

namespace SdnCustom.Client.Core.Runtime;

public sealed class AgentSupervisor : IAsyncDisposable
{
    private static readonly TimeSpan InitialReconnectDelay = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan MaximumReconnectDelay = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(5);

    private readonly IClientConfigStore _configStore;
    private readonly IDeviceConnectionFactory _deviceFactory;
    private readonly ConcurrentDictionary<string, SharedPointSample> _latestValues = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    private readonly object _statusGate = new();
    private CancellationTokenSource? _cancellation;
    private Task? _supervisorTask;
    private ClientConfiguration? _configuration;
    private IDeviceConnection? _device;
    private IAcquisitionLoop? _acquisitionLoop;
    private AsyncDuplexStreamingCall<AgentMessage, ServerMessage>? _activeCall;
    private ulong _sequence;
    private volatile bool _serverConnected;
    private volatile bool _deviceRunning;
    private SharedClientConnectionState? _lastReportedState;
    private string? _lastReportedMessage;

    public AgentSupervisor(
        IClientConfigStore configStore,
        IDeviceConnectionFactory? deviceFactory = null)
    {
        _configStore = configStore;
        _deviceFactory = deviceFactory ?? new DeviceConnectionFactory();
    }

    public event EventHandler<ClientRuntimeEvent>? RuntimeEvent;
    public event EventHandler<ClientLogEntry>? LogReceived;
    public event EventHandler<ClientConfiguration>? ConfigurationChanged;

    public bool IsServerConnected => _serverConnected;
    public bool IsDeviceRunning => _deviceRunning;
    public bool IsRunning => _supervisorTask is { IsCompleted: false };
    public IReadOnlyDictionary<string, SharedPointSample> LatestValues => _latestValues;

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (_supervisorTask is { IsCompleted: false })
        {
            return;
        }

        _configuration = await _configStore.LoadAsync(cancellationToken).ConfigureAwait(false);
        var errors = ClientConfigurationValidator.Validate(_configuration);
        if (errors.Count > 0)
        {
            throw new InvalidOperationException(string.Join(Environment.NewLine, errors));
        }

        _cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _supervisorTask = RunSupervisorAsync(_configuration, _cancellation.Token);
    }

    public async Task StopAsync()
    {
        if (_cancellation is null || _supervisorTask is null)
        {
            return;
        }

        await _cancellation.CancelAsync().ConfigureAwait(false);
        try
        {
            await _supervisorTask.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _cancellation.Dispose();
            _cancellation = null;
            _supervisorTask = null;
            await StopDeviceAsync("客户端已停止。").ConfigureAwait(false);
            SetServerConnected(false);
        }
    }

    public async Task StartCommunicationAsync(CancellationToken cancellationToken = default)
    {
        if (!_serverConnected)
        {
            throw new InvalidOperationException("必须首先连接服务端；服务端离线时禁止设备通讯。");
        }

        await StartDeviceAsync(cancellationToken).ConfigureAwait(false);
    }

    public Task StopCommunicationAsync() => StopDeviceAsync("通讯已由本地停止。");

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        _sendGate.Dispose();
    }

    private async Task RunSupervisorAsync(ClientConfiguration configuration, CancellationToken cancellationToken)
    {
        var failures = 0;
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await RunSessionAsync(configuration, cancellationToken).ConfigureAwait(false);
                failures = 0;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                failures++;
                Log("WARN", $"服务端连接异常：{exception.Message}");
                RaiseState(SharedClientConnectionState.Error, exception.Message);
            }
            finally
            {
                SetServerConnected(false);
                await StopDeviceAsync("服务端连接已断开，设备通讯立即停止。").ConfigureAwait(false);
                _activeCall = null;
            }

            var delaySeconds = Math.Min(
                MaximumReconnectDelay.TotalSeconds,
                InitialReconnectDelay.TotalSeconds * Math.Pow(2, Math.Max(0, failures - 1)));
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(delaySeconds), cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    private async Task RunSessionAsync(ClientConfiguration configuration, CancellationToken cancellationToken)
    {
        using var handler = CreateHttpHandler(configuration.Server);
        var scheme = configuration.Server.UseTls ? "https" : "http";
        using var channel = GrpcChannel.ForAddress(
            $"{scheme}://{configuration.Server.Host}:{configuration.Server.Port}",
            new GrpcChannelOptions { HttpHandler = handler });
        var client = new AgentGateway.AgentGatewayClient(channel);
        using var call = client.Connect(cancellationToken: cancellationToken);
        _activeCall = call;

        await SendAsync(BuildHello(configuration), cancellationToken).ConfigureAwait(false);
        if (!await call.ResponseStream.MoveNext(cancellationToken).ConfigureAwait(false))
        {
            throw new IOException("服务端在注册完成前关闭了连接。");
        }

        var first = call.ResponseStream.Current;
        if (first.PayloadCase != ServerMessage.PayloadOneofCase.Welcome || !first.Welcome.Accepted)
        {
            var reason = first.PayloadCase == ServerMessage.PayloadOneofCase.Welcome
                ? first.Welcome.Message
                : "服务端未返回欢迎消息。";
            throw new UnauthorizedAccessException(reason);
        }

        SetServerConnected(true);
        Log("INFO", $"已连接服务端，客户端代码 {configuration.ClientCode}。");
        RaiseState(SharedClientConnectionState.Disconnected, "已连接服务端，等待启动通讯。");

        using var heartbeatCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var heartbeatTask = SendHeartbeatsAsync(heartbeatCancellation.Token);
        try
        {
            await ApplyConfiguredPointsAsync(
                first.Welcome.ConfiguredPoints.Select(ContractMapper.FromContract),
                "已应用服务端下发的点表。",
                cancellationToken).ConfigureAwait(false);

            if (first.Welcome.DesiredRunning || configuration.AutoStart)
            {
                await TryStartDeviceAsync(cancellationToken).ConfigureAwait(false);
            }

            while (await call.ResponseStream.MoveNext(cancellationToken).ConfigureAwait(false))
            {
                await HandleServerMessageAsync(call.ResponseStream.Current, cancellationToken).ConfigureAwait(false);
            }
        }
        finally
        {
            await heartbeatCancellation.CancelAsync().ConfigureAwait(false);
            try
            {
                await heartbeatTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }

        throw new IOException("服务端连接已关闭。");
    }

    private async Task SendHeartbeatsAsync(CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(HeartbeatInterval);
        while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
        {
            await SendAsync(new AgentMessage
            {
                ClientCode = _configuration?.ClientCode ?? string.Empty,
                Sequence = NextSequence(),
                SentUnixNs = UnixTime.NowNanoseconds(),
                Ping = new Ping { TimestampUnixNs = UnixTime.NowNanoseconds() }
            }, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task HandleServerMessageAsync(ServerMessage message, CancellationToken cancellationToken)
    {
        switch (message.PayloadCase)
        {
            case ServerMessage.PayloadOneofCase.StartCommunication:
                await TryStartDeviceAsync(cancellationToken).ConfigureAwait(false);
                break;
            case ServerMessage.PayloadOneofCase.StopCommunication:
                await StopDeviceAsync("服务端停止通讯。").ConfigureAwait(false);
                break;
            case ServerMessage.PayloadOneofCase.WritePoints:
                await HandleWritePointsAsync(message.WritePoints, cancellationToken).ConfigureAwait(false);
                break;
            case ServerMessage.PayloadOneofCase.RequestSnapshot:
                await SendSnapshotAsync(cancellationToken).ConfigureAwait(false);
                break;
            case ServerMessage.PayloadOneofCase.ConfigurePoints:
                await ApplyConfiguredPointsAsync(
                    message.ConfigurePoints.Points.Select(ContractMapper.FromContract),
                    message.ConfigurePoints.Message,
                    cancellationToken).ConfigureAwait(false);
                break;
            case ServerMessage.PayloadOneofCase.Ping:
                await SendAsync(new AgentMessage
                {
                    ClientCode = _configuration?.ClientCode ?? string.Empty,
                    Sequence = NextSequence(),
                    SentUnixNs = UnixTime.NowNanoseconds(),
                    Ping = new Ping { TimestampUnixNs = message.Ping.TimestampUnixNs }
                }, cancellationToken).ConfigureAwait(false);
                break;
        }
    }

    private async Task ApplyConfiguredPointsAsync(
        IEnumerable<SharedPointDefinition> points,
        string message,
        CancellationToken cancellationToken)
    {
        if (_configuration is null)
        {
            return;
        }

        var wasRunning = _deviceRunning;
        if (wasRunning)
        {
            await StopDeviceAsync("服务端更新点表，正在重启设备通讯。").ConfigureAwait(false);
        }

        _configuration.Points = points.ToList();
        try
        {
            await _configStore.SaveAsync(_configuration, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            Log("ERROR", $"点表已应用，但本地保存失败：{exception.Message}");
            RaiseState(
                SharedClientConnectionState.Error,
                $"点表已应用，但本地保存失败：{exception.Message}");
        }
        ConfigurationChanged?.Invoke(this, _configuration);
        Log("INFO", $"{message} 共 {_configuration.Points.Count} 个测点。");

        if (wasRunning)
        {
            await TryStartDeviceAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task<bool> TryStartDeviceAsync(CancellationToken cancellationToken)
    {
        try
        {
            await StartDeviceAsync(cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private async Task StartDeviceAsync(CancellationToken cancellationToken)
    {
        if (_deviceRunning || _configuration is null)
        {
            return;
        }

        var configuration = _configuration;
        var options = new DeviceConnectionOptions
        {
            ClientCode = configuration.ClientCode,
            Protocol = configuration.Device.Protocol,
            Host = configuration.Device.Host,
            Port = configuration.Device.Port,
            OpcUaEndpoint = configuration.Device.OpcUaEndpoint,
            TimeoutMilliseconds = configuration.Device.TimeoutMilliseconds,
            Points = configuration.Points
        };

        try
        {
            _device = _deviceFactory.Create(options);
            RaiseState(SharedClientConnectionState.Connecting, $"正在连接 {configuration.Device.Protocol} 设备。");
            await _device.ConnectAsync(options, cancellationToken).ConfigureAwait(false);
            _acquisitionLoop = new AcquisitionLoop(
                _device,
                TimeSpan.FromMilliseconds(configuration.UserToCenterFrequencyMilliseconds));
            _acquisitionLoop.Faulted += OnAcquisitionFaultedAsync;
            await _acquisitionLoop.StartAsync(
                configuration.ClientCode,
                configuration.Points,
                SendBatchAsync,
                cancellationToken).ConfigureAwait(false);
            _deviceRunning = true;
            Log("INFO", $"设备通讯已启动：{configuration.Device.Protocol}。");
            RaiseState(SharedClientConnectionState.Connected, "设备通讯正常。");
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            await CleanupDeviceAfterStartFailureAsync().ConfigureAwait(false);
            throw;
        }
        catch (Exception exception)
        {
            await CleanupDeviceAfterStartFailureAsync().ConfigureAwait(false);
            Log("ERROR", $"设备启动失败：{exception.Message}");
            RaiseState(
                SharedClientConnectionState.Error,
                $"设备启动失败：{exception.Message}");
            throw;
        }
    }

    private async Task CleanupDeviceAfterStartFailureAsync()
    {
        _deviceRunning = false;
        if (_acquisitionLoop is not null)
        {
            _acquisitionLoop.Faulted -= OnAcquisitionFaultedAsync;
            try
            {
                await _acquisitionLoop.StopAsync().ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                Log("WARN", $"停止未完成的采集循环时发生异常：{exception.Message}");
            }

            try
            {
                await _acquisitionLoop.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                Log("WARN", $"释放未完成的设备连接时发生异常：{exception.Message}");
            }
        }
        else if (_device is not null)
        {
            try
            {
                await _device.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                Log("WARN", $"释放未完成的设备连接时发生异常：{exception.Message}");
            }
        }

        _acquisitionLoop = null;
        _device = null;
        _latestValues.Clear();
    }

    private async Task StopDeviceAsync(
        string reason,
        SharedClientConnectionState finalState = SharedClientConnectionState.Disconnected)
    {
        if (!_deviceRunning && _device is null)
        {
            Log("INFO", reason);
            RaiseState(finalState, reason);
            return;
        }

        _deviceRunning = false;
        if (_acquisitionLoop is not null)
        {
            _acquisitionLoop.Faulted -= OnAcquisitionFaultedAsync;
            try
            {
                await _acquisitionLoop.StopAsync().ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                Log("WARN", $"停止采集循环时发生异常：{exception.Message}");
            }

            try
            {
                await _acquisitionLoop.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                Log("WARN", $"释放设备连接时发生异常：{exception.Message}");
            }
            _acquisitionLoop = null;
        }
        else if (_device is not null)
        {
            try
            {
                await _device.DisposeAsync().ConfigureAwait(false);
            }
            catch (Exception exception)
            {
                Log("WARN", $"释放设备连接时发生异常：{exception.Message}");
            }
        }

        _device = null;
        _latestValues.Clear();
        Log("INFO", reason);
        RaiseState(finalState, reason);
    }

    private Task OnAcquisitionFaultedAsync(Exception exception)
    {
        Log("ERROR", $"设备采集失败：{exception.Message}");
        RaiseState(SharedClientConnectionState.Error, exception.Message);
        _ = StopDeviceAfterAcquisitionFaultAsync();
        return Task.CompletedTask;
    }

    private async Task StopDeviceAfterAcquisitionFaultAsync()
    {
        await Task.Yield();
        await StopDeviceAsync(
            "设备采集失败，通讯已停止。",
            SharedClientConnectionState.Error).ConfigureAwait(false);
    }

    private async Task SendBatchAsync(IReadOnlyList<SharedPointSample> samples, CancellationToken cancellationToken)
    {
        foreach (var sample in samples)
        {
            _latestValues[sample.PointId] = sample;
        }

        var batch = new PointBatch();
        batch.Values.AddRange(samples.Select(ContractMapper.ToContract));
        await SendAsync(new AgentMessage
        {
            ClientCode = _configuration?.ClientCode ?? string.Empty,
            Sequence = NextSequence(),
            SentUnixNs = UnixTime.NowNanoseconds(),
            ValueBatch = batch
        }, cancellationToken).ConfigureAwait(false);
        RaiseState(SharedClientConnectionState.Connected, "设备通讯正常。", samples);
    }

    private async Task SendSnapshotAsync(CancellationToken cancellationToken)
    {
        var snapshot = new PointSnapshot();
        snapshot.Values.AddRange(_latestValues.Values.Select(ContractMapper.ToContract));
        await SendAsync(new AgentMessage
        {
            ClientCode = _configuration?.ClientCode ?? string.Empty,
            Sequence = NextSequence(),
            SentUnixNs = UnixTime.NowNanoseconds(),
            Snapshot = snapshot
        }, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleWritePointsAsync(
        WritePoints command,
        CancellationToken cancellationToken)
    {
        var configuredPoints = (_configuration?.Points ?? [])
            .ToDictionary(point => point.PointId, StringComparer.Ordinal);
        var items = new List<DeviceWriteItem>(command.Points.Count);
        var preparationErrors = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var point in command.Points)
        {
            if (!configuredPoints.TryGetValue(point.PointId, out var configuredPoint))
            {
                preparationErrors[point.PointId] =
                    $"客户端点表中未配置 OUTPUT 端点 {point.PointId}。";
                continue;
            }

            if (configuredPoint.Direction != SharedPointDirection.Output)
            {
                preparationErrors[point.PointId] =
                    $"测点 {point.PointId} 不是客户端 OUTPUT 端点。";
                continue;
            }

            items.Add(new DeviceWriteItem(
                configuredPoint,
                ContractMapper.FromContractValue(
                    point.Value,
                    configuredPoint.DataType),
                point.TimestampUnixNs,
                string.IsNullOrWhiteSpace(point.SourceClientCode) ? null : point.SourceClientCode,
                string.IsNullOrWhiteSpace(point.SourcePointId) ? null : point.SourcePointId));
        }

        IReadOnlyList<DeviceWriteResult> deviceResults;
        try
        {
            var device = _device ?? throw new InvalidOperationException("设备尚未连接。");
            deviceResults = await device.WriteBatchAsync(
                _configuration?.ClientCode ?? string.Empty,
                items,
                cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception)
        {
            deviceResults = items
                .Select(item => new DeviceWriteResult(
                    item.Point.PointId,
                    false,
                    exception.Message))
                .ToArray();
        }

        var resultByPoint = deviceResults
            .GroupBy(result => result.PointId, StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.Ordinal);
        var response = new WriteResults
        {
            CommandId = command.CommandId,
            CompletedUnixNs = UnixTime.NowNanoseconds()
        };
        var writtenValues = new List<SharedPointSample>();
        foreach (var commandPoint in command.Points)
        {
            var item = items.FirstOrDefault(candidate =>
                candidate.Point.PointId == commandPoint.PointId);
            var result = preparationErrors.TryGetValue(commandPoint.PointId, out var preparationError)
                ? new DeviceWriteResult(commandPoint.PointId, false, preparationError)
                : resultByPoint.GetValueOrDefault(commandPoint.PointId) ??
                  new DeviceWriteResult(
                      commandPoint.PointId,
                      false,
                      "设备未返回该点写入结果。");
            response.Results.Add(new WritePointResult
            {
                PointId = commandPoint.PointId,
                Success = result.Success,
                ErrorMessage = result.ErrorMessage
            });
            if (!result.Success)
            {
                Log("ERROR", $"批量写入失败 {commandPoint.PointId}：{result.ErrorMessage}");
                continue;
            }

            if (item is null)
            {
                continue;
            }

            var sample = new SharedPointSample(
                _configuration?.ClientCode ?? string.Empty,
                item.Point.PointId,
                new SharedPointValue(
                    item.Value,
                    SharedPointQuality.Good,
                    item.TimestampUnixNanoseconds == 0
                        ? UnixTime.NowNanoseconds()
                        : item.TimestampUnixNanoseconds),
                item.SourceClientCode,
                item.SourcePointId);
            _latestValues[item.Point.PointId] = sample;
            writtenValues.Add(sample);
        }

        if (writtenValues.Count > 0)
        {
            RuntimeEvent?.Invoke(this, new ClientRuntimeEvent(
                SharedClientConnectionState.Connected,
                $"批量写出 {writtenValues.Count} 个输出点。",
                writtenValues));
        }

        await SendAsync(new AgentMessage
        {
            ClientCode = _configuration?.ClientCode ?? string.Empty,
            Sequence = NextSequence(),
            SentUnixNs = UnixTime.NowNanoseconds(),
            WriteResults = response
        }, cancellationToken).ConfigureAwait(false);
    }

    private AgentMessage BuildHello(ClientConfiguration configuration)
    {
        var hello = new Hello
        {
            ProtocolVersion = "1",
            ClientName = configuration.ClientName,
            AppVersion = typeof(AgentSupervisor).Assembly.GetName().Version?.ToString() ?? "1.0.0",
            InstanceId = Guid.NewGuid().ToString("N"),
            Protocol = ContractMapper.ToContract(configuration.Device.Protocol),
            EndpointDisplay = configuration.Device.Protocol == ClientProtocol.CustomTcp
                ? $"{configuration.Device.Host}:{configuration.Device.Port}"
                : configuration.Device.OpcUaEndpoint,
            CatalogRevision = ComputeCatalogRevision(configuration.Points)
        };
        hello.Points.AddRange(configuration.Points.Select(ContractMapper.ToContract));
        return new AgentMessage
        {
            ClientCode = configuration.ClientCode,
            Sequence = NextSequence(),
            SentUnixNs = UnixTime.NowNanoseconds(),
            Hello = hello
        };
    }

    private async Task SendAsync(AgentMessage message, CancellationToken cancellationToken)
    {
        var call = _activeCall ?? throw new IOException("服务端流未建立。");
        await _sendGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await call.RequestStream.WriteAsync(message, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private static SocketsHttpHandler CreateHttpHandler(ServerConnectionOptions options)
    {
        var handler = new SocketsHttpHandler
        {
            EnableMultipleHttp2Connections = true,
            ConnectTimeout = TimeSpan.FromSeconds(10)
        };

        if (options.UseTls)
        {
            handler.SslOptions = new SslClientAuthenticationOptions
            {
                RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                {
                    if (certificate is null)
                    {
                        return false;
                    }

                    if (!string.IsNullOrWhiteSpace(options.ServerCertificateThumbprint))
                    {
                        return certificate.GetCertHashString().Equals(
                            options.ServerCertificateThumbprint,
                            StringComparison.OrdinalIgnoreCase);
                    }

                    return options.AllowUntrustedServerCertificate;
                }
            };
        }

        return handler;
    }

    private static string ComputeCatalogRevision(IEnumerable<SharedPointDefinition> points)
    {
        var text = string.Join(
            "|",
            points.OrderBy(point => point.PointId, StringComparer.Ordinal)
                .Select(point => $"{point.PointId}:{point.Address}:{point.DataType}:{point.Direction}:{point.SourceInputPointId}"));
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(text)));
    }

    private ulong NextSequence() => Interlocked.Increment(ref _sequence);
    private void SetServerConnected(bool connected) => _serverConnected = connected;

    private void RaiseState(
        SharedClientConnectionState state,
        string message,
        IReadOnlyList<SharedPointSample>? samples = null)
    {
        RuntimeEvent?.Invoke(this, new ClientRuntimeEvent(state, message, samples));
        lock (_statusGate)
        {
            if (_lastReportedState == state &&
                string.Equals(_lastReportedMessage, message, StringComparison.Ordinal))
            {
                return;
            }

            _lastReportedState = state;
            _lastReportedMessage = message;
        }

        _ = SendStatusChangedAsync(state, message);
    }

    private async Task SendStatusChangedAsync(
        SharedClientConnectionState state,
        string message)
    {
        var cancellationToken = _cancellation?.Token ?? CancellationToken.None;
        if (cancellationToken.IsCancellationRequested || _activeCall is null)
        {
            return;
        }

        try
        {
            await SendAsync(new AgentMessage
            {
                ClientCode = _configuration?.ClientCode ?? string.Empty,
                Sequence = NextSequence(),
                SentUnixNs = UnixTime.NowNanoseconds(),
                StatusChanged = new ClientStatusChanged
                {
                    State = ContractMapper.ToContract(state),
                    Message = message
                }
            }, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // The connection itself is responsible for reporting transport failures.
        }
    }

    private void Log(string level, string message)
    {
        LogReceived?.Invoke(this, new ClientLogEntry(DateTimeOffset.Now, level, message));
    }
}
