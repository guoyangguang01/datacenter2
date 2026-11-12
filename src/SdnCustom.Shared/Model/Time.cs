namespace SdnCustom.Shared.Model;

public static class UnixTime
{
    public static long NowNanoseconds() =>
        DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() * 1_000_000L;

    public static DateTimeOffset FromNanoseconds(long unixNanoseconds) =>
        DateTimeOffset.FromUnixTimeMilliseconds(unixNanoseconds / 1_000_000L);
}
