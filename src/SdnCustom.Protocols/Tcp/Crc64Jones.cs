namespace SdnCustom.Protocols.Tcp;

internal static class Crc64Jones
{
    private const ulong ReflectedPolynomial = 0x95AC9329AC4BC9B5UL;
    private static readonly ulong[] LookupTable = CreateLookupTable();

    public static uint ComputeLow32(ReadOnlySpan<byte> data)
    {
        ulong checksum = 0;
        foreach (var value in data)
        {
            var tableIndex = (int)((checksum ^ value) & 0xFF);
            checksum = (checksum >> 8) ^ LookupTable[tableIndex];
        }

        return unchecked((uint)checksum);
    }

    private static ulong[] CreateLookupTable()
    {
        var table = new ulong[256];
        for (var index = 0; index < table.Length; index++)
        {
            var checksum = (ulong)index;
            for (var bit = 0; bit < 8; bit++)
            {
                checksum = (checksum & 1) != 0
                    ? (checksum >> 1) ^ ReflectedPolynomial
                    : checksum >> 1;
            }

            table[index] = checksum;
        }

        return table;
    }
}
