using SdnCustom.Shared.Model;

namespace SdnCustom.Client.Core.Runtime;

public interface IAcquisitionLoop : IAsyncDisposable
{
    bool IsRunning { get; }
    event Func<Exception, Task>? Faulted;
    Task StartAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        Func<IReadOnlyList<PointSample>, CancellationToken, Task> onChanged,
        CancellationToken cancellationToken = default);
    Task StopAsync();
}
