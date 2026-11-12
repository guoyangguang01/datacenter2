namespace SdnCustom.Protocols;

public interface IDeviceConnectionFactory
{
    IDeviceConnection Create(DeviceConnectionOptions options);
}
