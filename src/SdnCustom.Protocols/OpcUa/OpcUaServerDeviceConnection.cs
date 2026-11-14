using Microsoft.Extensions.Logging;
using Opc.Ua;
using Opc.Ua.Configuration;
using Opc.Ua.Server;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Protocols.OpcUa;

public sealed class OpcUaServerDeviceConnection : IDeviceConnection
{
    private readonly SemaphoreSlim _ioGate = new(1, 1);
    private ApplicationInstance? _application;
    private SdnPointNodeManager? _nodeManager;

    public ClientProtocol Protocol => ClientProtocol.OpcUaServer;
    public bool IsConnected => _nodeManager is not null;

    public async Task ConnectAsync(
        DeviceConnectionOptions options,
        CancellationToken cancellationToken = default)
    {
        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!Uri.TryCreate(options.OpcUaEndpoint, UriKind.Absolute, out var endpoint))
            {
                throw new InvalidOperationException($"OPC UA 服务端 Endpoint 无效：{options.OpcUaEndpoint}");
            }

            cancellationToken.ThrowIfCancellationRequested();
            var clientCode = string.IsNullOrWhiteSpace(options.ClientCode)
                ? "default"
                : options.ClientCode.Trim();
            var safeClientCode = string.Concat(clientCode.Select(character =>
                char.IsLetterOrDigit(character) || character is '-' or '_' ? character : '_'));
            var pkiRoot = Path.Combine(
                AppContext.BaseDirectory,
                "data",
                "opcua",
                safeClientCode,
                "pki");
            Directory.CreateDirectory(pkiRoot);

            var telemetry = DefaultTelemetry.Create(builder =>
            {
                builder.SetMinimumLevel(LogLevel.Warning);
            });
            var applicationUri = $"urn:sdncustom:opcua:{safeClientCode}";
            var application = new ApplicationInstance(telemetry)
            {
                ApplicationName = $"SDNCustom OPC UA Server ({clientCode})",
                ApplicationType = ApplicationType.Server
            };
            var subjectName = $"CN=SDNCustom OPC UA Server {safeClientCode}, DC=localhost";
            var certificates = new[]
            {
                new CertificateIdentifier
                {
                    StoreType = CertificateStoreType.Directory,
                    StorePath = Path.Combine(pkiRoot, "own"),
                    SubjectName = subjectName
                }
            }.ToArrayOf();
            var configuration = await application
                .Build(applicationUri, "urn:sdncustom:opcua:server")
                .SetTransportQuotas(new TransportQuotas
                {
                    OperationTimeout = Math.Max(1000, options.TimeoutMilliseconds),
                    MaxMessageSize = Math.Max(options.SizeLimitBytes, 1024 * 1024),
                    MaxBufferSize = Math.Max(options.SizeLimitBytes, 1024 * 1024)
                })
                .AsServer([endpoint.ToString()], [endpoint.ToString()])
                .AddUnsecurePolicyNone(true)
                .AddSecurityConfiguration(
                    certificates,
                    pkiRoot,
                    Path.Combine(pkiRoot, "rejected"))
                .SetAutoAcceptUntrustedCertificates(true)
                .CreateAsync(cancellationToken)
                .ConfigureAwait(false);

            await application.CheckApplicationInstanceCertificatesAsync(false)
                .ConfigureAwait(false);

            var factory = new SdnPointNodeManagerFactory(options.Points, applicationUri);
            var server = new StandardServer(telemetry);
            server.AddNodeManager(factory);
            await application.StartAsync(server).ConfigureAwait(false);

            _application = application;
            _nodeManager = factory.NodeManager
                           ?? throw new InvalidOperationException("OPC UA 节点管理器启动失败。");
        }
        finally
        {
            _ioGate.Release();
        }
    }

    public Task<IReadOnlyList<PointSample>> ReadAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var nodeManager = _nodeManager
                          ?? throw new IOException("内置 OPC UA 服务端尚未启动。");
        return Task.FromResult<IReadOnlyList<PointSample>>(
            points.Select(point => nodeManager.ReadPoint(clientCode, point)).ToArray());
    }

    public Task<IReadOnlyList<DeviceWriteResult>> WriteBatchAsync(
        string clientCode,
        IReadOnlyList<DeviceWriteItem> items,
        CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var nodeManager = _nodeManager
                          ?? throw new IOException("内置 OPC UA 服务端尚未启动。");
        var results = new List<DeviceWriteResult>(items.Count);
        foreach (var item in items)
        {
            try
            {
                nodeManager.WritePoint(item.Point, item.Value, item.TimestampUnixNanoseconds);
                results.Add(new DeviceWriteResult(item.Point.PointId, true));
            }
            catch (Exception exception) when (exception is not OperationCanceledException)
            {
                results.Add(new DeviceWriteResult(
                    item.Point.PointId,
                    false,
                    exception.Message));
            }
        }

        return Task.FromResult<IReadOnlyList<DeviceWriteResult>>(results);
    }

    public async Task DisconnectAsync(CancellationToken cancellationToken = default)
    {
        await _ioGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_application is null)
            {
                return;
            }

            await _application.StopAsync().ConfigureAwait(false);
            _application = null;
            _nodeManager = null;
        }
        finally
        {
            _ioGate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await DisconnectAsync().ConfigureAwait(false);
        _ioGate.Dispose();
    }
}

internal sealed class SdnPointNodeManagerFactory(
    IReadOnlyList<PointDefinition> points,
    string namespaceUri) : INodeManagerFactory
{
    public ArrayOf<string> NamespacesUris => [namespaceUri];
    public SdnPointNodeManager? NodeManager { get; private set; }

    public INodeManager Create(
        IServerInternal server,
        ApplicationConfiguration configuration)
    {
        NodeManager = new SdnPointNodeManager(server, configuration, points, namespaceUri);
        return NodeManager;
    }
}

internal sealed class SdnPointNodeManager : CustomNodeManager2
{
    private readonly IReadOnlyList<PointDefinition> _points;
    private readonly Dictionary<(string PointId, PointDirection Direction), BaseDataVariableState> _nodes = [];

    public SdnPointNodeManager(
        IServerInternal server,
        ApplicationConfiguration configuration,
        IReadOnlyList<PointDefinition> points,
        string namespaceUri)
        : base(server, configuration, namespaceUri)
    {
        _points = points;
    }

    public override void CreateAddressSpace(
        IDictionary<NodeId, IList<IReference>> externalReferences)
    {
        lock (Lock)
        {
            if (!externalReferences.TryGetValue(ObjectIds.ObjectsFolder, out var references))
            {
                externalReferences[ObjectIds.ObjectsFolder] = references = [];
            }

            var root = new FolderState(null)
            {
                NodeId = new NodeId("SDNCustom", NamespaceIndex),
                BrowseName = new QualifiedName("SDNCustom", NamespaceIndex),
                DisplayName = new LocalizedText("SDNCustom"),
                TypeDefinitionId = ObjectTypeIds.FolderType,
                EventNotifier = EventNotifiers.None,
                ReferenceTypeId = ReferenceTypeIds.Organizes
            };
            root.AddReference(ReferenceTypeIds.Organizes, true, ObjectIds.ObjectsFolder);
            references.Add(new NodeStateReference(
                ReferenceTypeIds.Organizes,
                false,
                root.NodeId));
            AddPredefinedNode(SystemContext, root);

            foreach (var point in _points)
            {
                var node = CreateVariable(root, point);
                _nodes[(point.PointId, point.Direction)] = node;
            }
        }
    }

    public PointSample ReadPoint(string clientCode, PointDefinition point)
    {
        lock (Lock)
        {
            var node = GetNode(point);
            var value = ConvertValue(node.Value, point.DataType);
            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() * 1_000_000;
            return new PointSample(
                clientCode,
                point.PointId,
                new PointValue(
                    value,
                    node.Value.IsNull ? PointQuality.Bad : PointQuality.Good,
                    timestamp));
        }
    }

    public void WritePoint(
        PointDefinition point,
        object? value,
        long timestampUnixNanoseconds)
    {
        lock (Lock)
        {
            var node = GetNode(point);
            node.Value = CreateVariant(value, point.DataType);
            node.StatusCode = StatusCodes.Good;
            node.Timestamp = DateTimeUtc.Now;
            node.ClearChangeMasks(SystemContext, false);
        }
    }

    private BaseDataVariableState CreateVariable(NodeState parent, PointDefinition point)
    {
        var nodeId = GetNodeId(point);
        if (_nodes.ContainsKey((point.PointId, point.Direction)) ||
            _nodes.Values.Any(node => node.NodeId == nodeId))
        {
            nodeId = new NodeId($"{point.PointId}:{point.Direction}", NamespaceIndex);
        }

        var writable = point.Direction == PointDirection.Input;
        var node = new BaseDataVariableState(parent)
        {
            NodeId = nodeId,
            BrowseName = new QualifiedName(point.PointId, NamespaceIndex),
            DisplayName = new LocalizedText(
                string.IsNullOrWhiteSpace(point.PointName)
                    ? point.PointId
                    : point.PointName),
            Description = new LocalizedText($"{point.Direction} · {point.Group}"),
            TypeDefinitionId = VariableTypeIds.BaseDataVariableType,
            DataType = GetDataType(point.DataType),
            ValueRank = ValueRanks.Scalar,
            AccessLevel = writable
                ? AccessLevels.CurrentReadOrWrite
                : AccessLevels.CurrentRead,
            UserAccessLevel = writable
                ? AccessLevels.CurrentReadOrWrite
                : AccessLevels.CurrentRead,
            Historizing = false,
            Value = CreateVariant(DefaultValue(point.DataType), point.DataType),
            StatusCode = StatusCodes.Good,
            Timestamp = DateTimeUtc.Now,
            ReferenceTypeId = ReferenceTypeIds.Organizes
        };
        parent.AddChild(node);
        AddPredefinedNode(SystemContext, node);
        return node;
    }

    private BaseDataVariableState GetNode(PointDefinition point) =>
        _nodes.TryGetValue((point.PointId, point.Direction), out var node)
            ? node
            : throw new KeyNotFoundException(
                $"OPC UA 节点不存在：{point.PointId} ({point.Direction})");

    private NodeId GetNodeId(PointDefinition point)
    {
        if (!string.IsNullOrWhiteSpace(point.Address))
        {
            try
            {
                var parsed = NodeId.Parse(point.Address);
                return new NodeId(parsed.IdentifierAsString, NamespaceIndex);
            }
            catch (ServiceResultException)
            {
            }
        }

        return new NodeId(point.PointId, NamespaceIndex);
    }

    private static NodeId GetDataType(PointDataType dataType) => dataType switch
    {
        PointDataType.Bool => DataTypeIds.Boolean,
        PointDataType.Int16 => DataTypeIds.Int16,
        PointDataType.Int32 => DataTypeIds.Int32,
        PointDataType.Float32 => DataTypeIds.Float,
        PointDataType.Float64 => DataTypeIds.Double,
        PointDataType.String => DataTypeIds.String,
        _ => DataTypeIds.BaseDataType
    };

    private static object? DefaultValue(PointDataType dataType) => dataType switch
    {
        PointDataType.Bool => false,
        PointDataType.Int16 => (short)0,
        PointDataType.Int32 => 0,
        PointDataType.Float32 => 0f,
        PointDataType.Float64 => 0d,
        PointDataType.String => string.Empty,
        _ => null
    };

    private static Variant CreateVariant(object? value, PointDataType dataType)
    {
        var converted = PointTypeConverter.Convert(value, dataType);
        return converted switch
        {
            bool boolean => Variant.From(boolean),
            short int16 => Variant.From(int16),
            int int32 => Variant.From(int32),
            float single => Variant.From(single),
            double number => Variant.From(number),
            string text => Variant.From(text),
            _ => Variant.Null
        };
    }

    private static object? ConvertValue(Variant value, PointDataType dataType)
    {
        if (value.IsNull)
        {
            return null;
        }

        return dataType switch
        {
            PointDataType.Bool when value.TryGetValue(out bool boolean) => boolean,
            PointDataType.Int16 when value.TryGetValue(out short int16) => int16,
            PointDataType.Int32 when value.TryGetValue(out int int32) => int32,
            PointDataType.Float32 when value.TryGetValue(out float single) => single,
            PointDataType.Float64 when value.TryGetValue(out double number) => number,
            PointDataType.String when value.TryGetValue(out string? text) => text,
            _ => null
        };
    }
}
