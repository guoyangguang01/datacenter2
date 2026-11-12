using Grpc.Core;
using SdnCustom.Contracts;
using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Server.Persistence;
using SdnCustom.Server.Runtime;
using SdnCustom.Shared.Model;
using ContractPointSample = SdnCustom.Contracts.Agent.V1.PointSample;
using SharedClientConnectionState = SdnCustom.Shared.Model.ClientConnectionState;
using SharedPointDataType = SdnCustom.Shared.Model.PointDataType;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;
using SharedPointSample = SdnCustom.Shared.Model.PointSample;

namespace SdnCustom.Server.Grpc;

public sealed class AgentGatewayService : AgentGateway.AgentGatewayBase
{
    private readonly IClientSystemStore _clients;
    private readonly IAgentRegistry _agents;
    private readonly IPointRouter _router;
    private readonly ITelemetryService _telemetry;
    private readonly IServerLogStore _logs;

    public AgentGatewayService(
        IClientSystemStore clients,
        IAgentRegistry agents,
        IPointRouter router,
        ITelemetryService telemetry,
        IServerLogStore logs)
    {
        _clients = clients;
        _agents = agents;
        _router = router;
        _telemetry = telemetry;
        _logs = logs;
    }

    public override async Task Connect(
        IAsyncStreamReader<AgentMessage> requestStream,
        IServerStreamWriter<ServerMessage> responseStream,
        ServerCallContext context)
    {
        string? clientCode = null;
        string? instanceId = null;
        IAgentSession? session = null;
        IReadOnlyList<SharedPointDefinition> registeredPoints = [];

        try
        {
            if (!await requestStream.MoveNext(context.CancellationToken).ConfigureAwait(false))
            {
                return;
            }

            var first = requestStream.Current;
            if (first.PayloadCase != AgentMessage.PayloadOneofCase.Hello ||
                string.IsNullOrWhiteSpace(first.ClientCode))
            {
                await responseStream.WriteAsync(new ServerMessage
                {
                    Welcome = new Welcome { Accepted = false, Message = "首个消息必须是带 client_code 的 Hello。" },
                    SentUnixNs = UnixTime.NowNanoseconds()
                });
                return;
            }

            clientCode = first.ClientCode.Trim();
            instanceId = first.Hello.InstanceId;
            var registration = new ClientRegistration(
                clientCode,
                first.Hello.ClientName,
                ContractMapper.FromContract(first.Hello.Protocol),
                first.Hello.EndpointDisplay,
                first.Hello.AppVersion,
                first.Hello.CatalogRevision,
                first.Hello.Points.Select(ContractMapper.FromContract).ToArray());

            ClientSystemRecord client;
            try
            {
                client = await _clients.RegisterAsync(registration, context.CancellationToken).ConfigureAwait(false);
            }
            catch (UnauthorizedAccessException exception)
            {
                await responseStream.WriteAsync(new ServerMessage
                {
                    Welcome = new Welcome { Accepted = false, Message = exception.Message },
                    SentUnixNs = UnixTime.NowNanoseconds()
                });
                return;
            }

            session = new AgentSession(clientCode, instanceId, responseStream);
            if (!_agents.TryRegister(session, out var reason))
            {
                await responseStream.WriteAsync(new ServerMessage
                {
                    Welcome = new Welcome { Accepted = false, Message = reason },
                    SentUnixNs = UnixTime.NowNanoseconds()
                });
                await session.DisposeAsync().ConfigureAwait(false);
                session = null;
                return;
            }

            registeredPoints = client.Points;
            _router.RegisterClient(clientCode, registeredPoints);
            var welcome = new Welcome
            {
                Accepted = true,
                DesiredRunning = client.DesiredRunning,
                ServerUnixNs = UnixTime.NowNanoseconds(),
                Message = "注册成功。",
                CatalogRevision = client.CatalogRevision
            };
            welcome.ConfiguredPoints.AddRange(client.Points.Select(ContractMapper.ToContract));
            await session.SendAsync(new ServerMessage
            {
                Welcome = welcome,
                SentUnixNs = UnixTime.NowNanoseconds()
            }, context.CancellationToken).ConfigureAwait(false);
            _logs.Add("INFO", "连接", $"客户端上线：{clientCode}（{registration.Protocol}）。");

            using var streamCancellation =
                CancellationTokenSource.CreateLinkedTokenSource(context.CancellationToken);
            var watchdog = MonitorHeartbeatAsync(session, streamCancellation);
            try
            {
                while (await requestStream.MoveNext(streamCancellation.Token).ConfigureAwait(false))
                {
                    session.Touch();
                    await HandleMessageAsync(
                        requestStream.Current,
                        clientCode,
                        streamCancellation.Token).ConfigureAwait(false);
                }
            }
            finally
            {
                await streamCancellation.CancelAsync().ConfigureAwait(false);
                try
                {
                    await watchdog.ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                }
            }
        }
        catch (OperationCanceledException) when (context.CancellationToken.IsCancellationRequested)
        {
        }
        catch (OperationCanceledException)
        {
            _logs.Add("WARN", "连接", $"客户端心跳超时：{clientCode}");
        }
        catch (Exception exception)
        {
            _logs.Add("ERROR", "连接", $"客户端流异常：{exception.Message}");
            throw;
        }
        finally
        {
            if (clientCode is not null && instanceId is not null)
            {
                _agents.Unregister(clientCode, instanceId);
                if (registeredPoints.Count > 0)
                {
                    await _telemetry.MarkClientOfflineAsync(clientCode, registeredPoints).ConfigureAwait(false);
                }
            }

            if (session is not null)
            {
                await session.DisposeAsync().ConfigureAwait(false);
            }
        }
    }

    private async Task HandleMessageAsync(
        AgentMessage message,
        string clientCode,
        CancellationToken cancellationToken)
    {
        var pointTypes = _router.GetClientPoints(clientCode)
            .ToDictionary(point => point.PointId, point => point.DataType, StringComparer.Ordinal);
        switch (message.PayloadCase)
        {
            case AgentMessage.PayloadOneofCase.ValueBatch:
                await _telemetry.HandleBatchAsync(
                    clientCode,
                    ConvertSamples(message.ValueBatch.Values, pointTypes),
                    false,
                    cancellationToken).ConfigureAwait(false);
                break;
            case AgentMessage.PayloadOneofCase.Snapshot:
                await _telemetry.HandleBatchAsync(
                    clientCode,
                    ConvertSamples(message.Snapshot.Values, pointTypes),
                    true,
                    cancellationToken).ConfigureAwait(false);
                break;
            case AgentMessage.PayloadOneofCase.WriteResult:
                await _telemetry.HandleWriteResultAsync(
                    clientCode,
                    message.WriteResult,
                    cancellationToken).ConfigureAwait(false);
                break;
            case AgentMessage.PayloadOneofCase.StatusChanged:
                _logs.Add(
                    message.StatusChanged.State == SdnCustom.Contracts.Agent.V1.ClientConnectionState.Error ? "ERROR" : "INFO",
                    "客户端状态",
                    $"{clientCode}: {message.StatusChanged.Message}");
                break;
            case AgentMessage.PayloadOneofCase.LogEvent:
                _logs.Add(message.LogEvent.Level, clientCode, message.LogEvent.Message);
                break;
            case AgentMessage.PayloadOneofCase.Ping:
                break;
        }
    }

    private static IReadOnlyList<SharedPointSample> ConvertSamples(
        IEnumerable<ContractPointSample> values,
        IReadOnlyDictionary<string, SharedPointDataType> pointTypes)
    {
        var result = new List<SharedPointSample>();
        foreach (var value in values)
        {
            if (!pointTypes.TryGetValue(value.PointId, out var dataType))
            {
                continue;
            }

            result.Add(ContractMapper.FromContract(value, dataType));
        }

        return result;
    }

    private async Task MonitorHeartbeatAsync(IAgentSession session, CancellationTokenSource cancellation)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
        while (await timer.WaitForNextTickAsync(cancellation.Token).ConfigureAwait(false))
        {
            if (session.IdleTime > TimeSpan.FromSeconds(15))
            {
                await cancellation.CancelAsync().ConfigureAwait(false);
                return;
            }
        }
    }
}
