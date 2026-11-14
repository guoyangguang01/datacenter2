namespace SdnCustom.Server.Runtime;

public interface IServerCommunicationGate
{
    bool IsRunning { get; }
    void Start();
    void Stop();
}

public sealed class ServerCommunicationGate : IServerCommunicationGate
{
    private volatile bool _isRunning;

    public bool IsRunning => _isRunning;
    public void Start() => _isRunning = true;
    public void Stop() => _isRunning = false;
}
