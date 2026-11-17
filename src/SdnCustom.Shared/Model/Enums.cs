namespace SdnCustom.Shared.Model;

public enum ClientProtocol
{
    Tcp,
    OpcUaClient,
    OpcUaServer
}

public enum PointDirection
{
    // 数据从第三方进入软件侧，作为分发来源。
    Input,

    // 软件侧向一个或多个集成方发送数据。
    Output
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
