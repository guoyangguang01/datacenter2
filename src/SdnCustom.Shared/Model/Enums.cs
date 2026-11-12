namespace SdnCustom.Shared.Model;

public enum ClientProtocol
{
    CustomTcp,
    OpcUa
}

public enum PointDirection
{
    Output,
    Input
}

public enum PointDataType
{
    Bool,
    Int16,
    Int32,
    Float32,
    Float64,
    String
}

public enum PointQuality
{
    Good,
    Bad,
    Uncertain,
    CommLost
}

public enum ClientConnectionState
{
    Disconnected,
    Connecting,
    Connected,
    Error
}
