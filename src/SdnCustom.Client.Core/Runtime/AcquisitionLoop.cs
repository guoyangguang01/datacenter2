using SdnCustom.Protocols;
using SdnCustom.Shared;
using SdnCustom.Shared.Model;

namespace SdnCustom.Client.Core.Runtime;

public sealed class AcquisitionLoop : IAcquisitionLoop
{
    private readonly IDeviceConnection _device;
    private readonly TimeSpan _interval;
    private readonly ChangeGate _changeGate = new();
    private CancellationTokenSource? _cancellation;
    private Task? _loopTask;

    public AcquisitionLoop(IDeviceConnection device, TimeSpan? interval = null)
    {
        _device = device;
        _interval = interval ?? TimeSpan.FromMilliseconds(200);
    }

    public bool IsRunning => _loopTask is { IsCompleted: false };

    public event Func<Exception, Task>? Faulted;

    public Task StartAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        Func<IReadOnlyList<PointSample>, CancellationToken, Task> onChanged,
        CancellationToken cancellationToken = default)
    {
        if (IsRunning)
        {
            return Task.CompletedTask;
        }

        _cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _loopTask = RunAsync(clientCode, points, onChanged, _cancellation.Token);
        return Task.CompletedTask;
    }

    public async Task StopAsync()
    {
        if (_cancellation is null || _loopTask is null)
        {
            return;
        }

        await _cancellation.CancelAsync().ConfigureAwait(false);
        try
        {
            await _loopTask.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            _cancellation.Dispose();
            _cancellation = null;
            _loopTask = null;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync().ConfigureAwait(false);
        await _device.DisposeAsync().ConfigureAwait(false);
    }

    private async Task RunAsync(
        string clientCode,
        IReadOnlyList<PointDefinition> points,
        Func<IReadOnlyList<PointSample>, CancellationToken, Task> onChanged,
        CancellationToken cancellationToken)
    {
        var outputPoints = points
            .Where(point => point.Direction == PointDirection.Output)
            .ToArray();
        using var timer = new PeriodicTimer(_interval);

        try
        {
            do
            {
                if (outputPoints.Length == 0)
                {
                    continue;
                }

                var samples = await _device.ReadAsync(clientCode, outputPoints, cancellationToken).ConfigureAwait(false);
                var changed = _changeGate.Filter(clientCode, outputPoints, samples);
                if (changed.Count > 0)
                {
                    await onChanged(changed, cancellationToken).ConfigureAwait(false);
                }
            }
            while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false));
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception exception)
        {
            if (Faulted is not null)
            {
                await Faulted(exception).ConfigureAwait(false);
            }
        }
    }
}
