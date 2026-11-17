using Google.Protobuf.WellKnownTypes;
using SdnCustom.Contracts.Agent.V1;
using SdnCustom.Shared;
using ContractPointDataType = SdnCustom.Contracts.Agent.V1.PointDataType;
using ContractPointDirection = SdnCustom.Contracts.Agent.V1.PointDirection;
using ContractPointQuality = SdnCustom.Contracts.Agent.V1.PointQuality;
using ContractPointSample = SdnCustom.Contracts.Agent.V1.PointSample;
using SharedClientConnectionState = SdnCustom.Shared.Model.ClientConnectionState;
using SharedClientProtocol = SdnCustom.Shared.Model.ClientProtocol;
using SharedPointDataType = SdnCustom.Shared.Model.PointDataType;
using SharedPointDefinition = SdnCustom.Shared.Model.PointDefinition;
using SharedPointDirection = SdnCustom.Shared.Model.PointDirection;
using SharedPointQuality = SdnCustom.Shared.Model.PointQuality;
using SharedPointSample = SdnCustom.Shared.Model.PointSample;
using SharedPointValue = SdnCustom.Shared.Model.PointValue;

namespace SdnCustom.Contracts;

public static class ContractMapper
{
    public static DeviceProtocol ToContract(SharedClientProtocol protocol) => protocol switch
    {
        SharedClientProtocol.Tcp => DeviceProtocol.Tcp,
        SharedClientProtocol.OpcUaClient => DeviceProtocol.OpcUaClient,
        SharedClientProtocol.OpcUaServer => DeviceProtocol.OpcUaServer,
        _ => DeviceProtocol.Unspecified
    };

    public static SharedClientProtocol FromContract(DeviceProtocol protocol) => protocol switch
    {
        DeviceProtocol.Tcp => SharedClientProtocol.Tcp,
        DeviceProtocol.OpcUaClient => SharedClientProtocol.OpcUaClient,
        DeviceProtocol.OpcUaServer => SharedClientProtocol.OpcUaServer,
        _ => SharedClientProtocol.Tcp
    };

    public static ContractPointDirection ToContract(SharedPointDirection direction) =>
        direction == SharedPointDirection.Output
            ? ContractPointDirection.Output
            : ContractPointDirection.Input;

    public static SharedPointDirection FromContract(ContractPointDirection direction) =>
        direction == ContractPointDirection.Output
            ? SharedPointDirection.Output
            : SharedPointDirection.Input;

    public static ContractPointDataType ToContract(SharedPointDataType dataType) => dataType switch
    {
        SharedPointDataType.Bool => ContractPointDataType.Bool,
        SharedPointDataType.Int16 => ContractPointDataType.Int16,
        SharedPointDataType.Int32 => ContractPointDataType.Int32,
        SharedPointDataType.Float32 => ContractPointDataType.Float32,
        SharedPointDataType.Float64 => ContractPointDataType.Float64,
        SharedPointDataType.String => ContractPointDataType.String,
        _ => ContractPointDataType.Unspecified
    };

    public static SharedPointDataType FromContract(ContractPointDataType dataType) => dataType switch
    {
        ContractPointDataType.Bool => SharedPointDataType.Bool,
        ContractPointDataType.Int16 => SharedPointDataType.Int16,
        ContractPointDataType.Int32 => SharedPointDataType.Int32,
        ContractPointDataType.Float32 => SharedPointDataType.Float32,
        ContractPointDataType.Float64 => SharedPointDataType.Float64,
        ContractPointDataType.String => SharedPointDataType.String,
        _ => SharedPointDataType.Float64
    };

    public static ContractPointQuality ToContract(SharedPointQuality quality) => quality switch
    {
        SharedPointQuality.Good => ContractPointQuality.Good,
        SharedPointQuality.Bad => ContractPointQuality.Bad,
        SharedPointQuality.Uncertain => ContractPointQuality.Uncertain,
        SharedPointQuality.CommLost => ContractPointQuality.CommLost,
        _ => ContractPointQuality.Unspecified
    };

    public static SharedPointQuality FromContract(ContractPointQuality quality) => quality switch
    {
        ContractPointQuality.Good => SharedPointQuality.Good,
        ContractPointQuality.Bad => SharedPointQuality.Bad,
        ContractPointQuality.Uncertain => SharedPointQuality.Uncertain,
        ContractPointQuality.CommLost => SharedPointQuality.CommLost,
        _ => SharedPointQuality.Bad
    };

    public static SharedClientConnectionState FromContract(SdnCustom.Contracts.Agent.V1.ClientConnectionState state) => state switch
    {
        SdnCustom.Contracts.Agent.V1.ClientConnectionState.Connected => SharedClientConnectionState.Connected,
        SdnCustom.Contracts.Agent.V1.ClientConnectionState.Connecting => SharedClientConnectionState.Connecting,
        SdnCustom.Contracts.Agent.V1.ClientConnectionState.Error => SharedClientConnectionState.Error,
        _ => SharedClientConnectionState.Disconnected
    };

    public static SdnCustom.Contracts.Agent.V1.ClientConnectionState ToContract(SharedClientConnectionState state) => state switch
    {
        SharedClientConnectionState.Connected => SdnCustom.Contracts.Agent.V1.ClientConnectionState.Connected,
        SharedClientConnectionState.Connecting => SdnCustom.Contracts.Agent.V1.ClientConnectionState.Connecting,
        SharedClientConnectionState.Error => SdnCustom.Contracts.Agent.V1.ClientConnectionState.Error,
        _ => SdnCustom.Contracts.Agent.V1.ClientConnectionState.Disconnected
    };

    public static SdnCustom.Contracts.Agent.V1.PointDefinition ToContract(SharedPointDefinition point) => new()
    {
        PointId = point.PointId,
        PointName = point.PointName,
        Address = point.Address,
        DataType = ToContract(point.DataType),
        Unit = point.Unit,
        Direction = ToContract(point.Direction),
        SourceInputPointId = point.SourceInputPointId ?? string.Empty,
        Deadband = point.Deadband,
        Group = point.Group
    };

    public static SharedPointDefinition FromContract(SdnCustom.Contracts.Agent.V1.PointDefinition point) => new()
    {
        PointId = point.PointId,
        PointName = point.PointName,
        Address = point.Address,
        DataType = FromContract(point.DataType),
        Unit = point.Unit,
        Direction = FromContract(point.Direction),
        SourceInputPointId = string.IsNullOrWhiteSpace(point.SourceInputPointId) ? null : point.SourceInputPointId,
        Deadband = point.Deadband,
        Group = point.Group
    };

    public static TypedValue ToContractValue(object? value)
    {
        var result = new TypedValue();
        switch (value)
        {
            case null:
                break;
            case bool boolean:
                result.BoolValue = boolean;
                break;
            case string text:
                result.StringValue = text;
                break;
            case byte or sbyte or short or ushort or int or uint or long or ulong:
                result.IntValue = Convert.ToInt64(value);
                break;
            case float or double or decimal:
                result.DoubleValue = Convert.ToDouble(value);
                break;
            default:
                result.StringValue = value.ToString();
                break;
        }

        return result;
    }

    public static object? FromContractValue(TypedValue value, SharedPointDataType dataType)
    {
        object? raw = value.KindCase switch
        {
            TypedValue.KindOneofCase.BoolValue => value.BoolValue,
            TypedValue.KindOneofCase.IntValue => value.IntValue,
            TypedValue.KindOneofCase.DoubleValue => value.DoubleValue,
            TypedValue.KindOneofCase.StringValue => value.StringValue,
            _ => null
        };

        return PointTypeConverter.Convert(raw, dataType);
    }

    public static ContractPointSample ToContract(SharedPointSample sample) => new()
    {
        ClientCode = sample.ClientCode,
        PointId = sample.PointId,
        Value = ToContractValue(sample.Value.Value),
        Quality = ToContract(sample.Value.Quality),
        TimestampUnixNs = sample.Value.TimestampUnixNanoseconds,
        SourceClientCode = sample.SourceClientCode ?? string.Empty,
        SourcePointId = sample.SourcePointId ?? string.Empty
    };

    public static SharedPointSample FromContract(ContractPointSample sample, SharedPointDataType dataType) => new(
        sample.ClientCode,
        sample.PointId,
        new SharedPointValue(
            FromContractValue(sample.Value, dataType),
            FromContract(sample.Quality),
            sample.TimestampUnixNs),
        string.IsNullOrWhiteSpace(sample.SourceClientCode) ? null : sample.SourceClientCode,
        string.IsNullOrWhiteSpace(sample.SourcePointId) ? null : sample.SourcePointId);

    public static Timestamp ToTimestamp(long unixNanoseconds) =>
        Timestamp.FromDateTimeOffset(SdnCustom.Shared.Model.UnixTime.FromNanoseconds(unixNanoseconds));
}
