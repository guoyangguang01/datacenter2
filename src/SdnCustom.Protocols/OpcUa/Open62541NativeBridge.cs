using System.Runtime.InteropServices;
using System.Text;

namespace SdnCustom.Protocols.OpcUa;

public interface IOpen62541NativeBridge
{
    nint Create();
    int Connect(nint handle, string endpoint);
    int Disconnect(nint handle);
    int Read(nint handle, string nodeId, int dataType, Span<byte> valueBuffer, out int valueLength, out long timestampUnixNanoseconds);
    int Write(nint handle, string nodeId, int dataType, string value);
    void Destroy(nint handle);
}

public sealed class Open62541NativeBridge : IOpen62541NativeBridge
{
    private const string LibraryName = "sdncustom_opcua";

    public nint Create()
    {
        try
        {
            return SdnCreate();
        }
        catch (DllNotFoundException exception)
        {
            throw new PlatformNotSupportedException(
                "The open62541 bridge library is missing. Build native/open62541-bridge and copy " +
                "sdncustom_opcua.dll (Windows) or libsdncustom_opcua.so (Linux) beside the client executable.",
                exception);
        }
    }

    public int Connect(nint handle, string endpoint) => SdnConnect(handle, endpoint);
    public int Disconnect(nint handle) => SdnDisconnect(handle);

    public int Read(
        nint handle,
        string nodeId,
        int dataType,
        Span<byte> valueBuffer,
        out int valueLength,
        out long timestampUnixNanoseconds)
    {
        var buffer = valueBuffer.ToArray();
        var result = SdnRead(handle, nodeId, dataType, buffer, buffer.Length, out valueLength, out timestampUnixNanoseconds);
        buffer.AsSpan(0, Math.Clamp(valueLength, 0, buffer.Length)).CopyTo(valueBuffer);
        return result;
    }

    public int Write(nint handle, string nodeId, int dataType, string value) =>
        SdnWrite(handle, nodeId, dataType, value);

    public void Destroy(nint handle) => SdnDestroy(handle);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern nint SdnCreate();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int SdnConnect(nint handle, [MarshalAs(UnmanagedType.LPUTF8Str)] string endpoint);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int SdnDisconnect(nint handle);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int SdnRead(
        nint handle,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string nodeId,
        int dataType,
        byte[] buffer,
        int capacity,
        out int valueLength,
        out long timestampUnixNanoseconds);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern int SdnWrite(
        nint handle,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string nodeId,
        int dataType,
        [MarshalAs(UnmanagedType.LPUTF8Str)] string value);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern void SdnDestroy(nint handle);
}
