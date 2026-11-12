using System.Collections.Concurrent;
using SdnCustom.Shared.Model;

namespace SdnCustom.Shared;

public sealed class ChangeGate
{
    private readonly ConcurrentDictionary<PointKey, PointValue> _lastValues = new();

    public IReadOnlyList<PointSample> Filter(
        string clientCode,
        IEnumerable<PointDefinition> definitions,
        IEnumerable<PointSample> samples)
    {
        var points = definitions.ToDictionary(point => point.PointId, StringComparer.Ordinal);
        var changed = new List<PointSample>();

        foreach (var sample in samples)
        {
            if (!points.TryGetValue(sample.PointId, out var definition))
            {
                continue;
            }

            var key = PointKey.Create(clientCode, sample.PointId);
            if (_lastValues.TryGetValue(key, out var previous) && !IsEffectiveChange(previous, sample.Value, definition.Deadband))
            {
                continue;
            }

            _lastValues[key] = sample.Value;
            changed.Add(sample);
        }

        return changed;
    }

    public void Remove(PointKey key) => _lastValues.TryRemove(key, out _);

    public void RemoveClient(string clientCode)
    {
        foreach (var key in _lastValues.Keys.Where(key => key.ClientCode == clientCode))
        {
            _lastValues.TryRemove(key, out _);
        }
    }

    public static bool IsEffectiveChange(PointValue previous, PointValue current, double deadband)
    {
        if (previous.Quality != current.Quality)
        {
            return true;
        }

        if (previous.Value is null || current.Value is null)
        {
            return previous.Value is null != current.Value is null;
        }

        if (TryToDouble(previous.Value, out var oldNumber) && TryToDouble(current.Value, out var newNumber))
        {
            return Math.Abs(newNumber - oldNumber) > Math.Max(0, deadband);
        }

        return !previous.Value.Equals(current.Value);
    }

    private static bool TryToDouble(object value, out double result)
    {
        switch (value)
        {
            case byte number:
                result = number;
                return true;
            case short number:
                result = number;
                return true;
            case int number:
                result = number;
                return true;
            case long number:
                result = number;
                return true;
            case float number:
                result = number;
                return true;
            case double number:
                result = number;
                return true;
            case decimal number:
                result = (double)number;
                return true;
            default:
                result = 0;
                return false;
        }
    }
}
