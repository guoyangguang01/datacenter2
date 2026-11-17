using System.Buffers;
using System.Buffers.Binary;
using System.Text;

namespace SdnCustom.Protocols.Tcp;

public sealed record TcpValue(string PointId, object? Value);

public static class TcpMessageCodec
{
    private const int MaximumDepth = 64;

    public static byte[] EncodeValues(IEnumerable<TcpValue> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var items = values.ToArray();
        var writer = new ArrayBufferWriter<byte>(Math.Max(64, items.Length * 32));
        WriteMapHeader(writer, 1);
        WriteString(writer, "datas");
        WriteArrayHeader(writer, items.Length);
        foreach (var item in items)
        {
            WriteMapHeader(writer, 2);
            WriteString(writer, "id");
            WriteString(writer, item.PointId);
            WriteString(writer, "value");
            WriteValue(writer, item.Value);
        }

        return writer.WrittenSpan.ToArray();
    }

    public static IReadOnlyList<TcpValue> DecodeValues(ReadOnlySpan<byte> body)
    {
        if (body.IsEmpty)
        {
            return [];
        }

        var reader = new Reader(body);
        var root = reader.ReadValue(0);
        if (root is not Dictionary<string, object?> rootMap ||
            !rootMap.TryGetValue("datas", out var rawItems) ||
            rawItems is not List<object?> items)
        {
            return [];
        }

        var values = new List<TcpValue>(items.Count);
        foreach (var rawItem in items)
        {
            if (rawItem is not Dictionary<string, object?> item ||
                !item.TryGetValue("id", out var rawId) ||
                rawId is not string pointId)
            {
                continue;
            }

            item.TryGetValue("value", out var value);
            values.Add(new TcpValue(pointId, value));
        }

        return values;
    }

    private static void WriteValue(IBufferWriter<byte> writer, object? value)
    {
        switch (value)
        {
            case null:
                WriteByte(writer, 0xC0);
                return;
            case bool boolean:
                WriteByte(writer, boolean ? (byte)0xC3 : (byte)0xC2);
                return;
            case string text:
                WriteString(writer, text);
                return;
            case byte number:
                WriteUnsignedInteger(writer, number);
                return;
            case sbyte number:
                WriteSignedInteger(writer, number);
                return;
            case short number:
                WriteSignedInteger(writer, number);
                return;
            case ushort number:
                WriteUnsignedInteger(writer, number);
                return;
            case int number:
                WriteSignedInteger(writer, number);
                return;
            case uint number:
                WriteUnsignedInteger(writer, number);
                return;
            case long number:
                WriteSignedInteger(writer, number);
                return;
            case ulong number:
                WriteUnsignedInteger(writer, number);
                return;
            case float number:
                WriteByte(writer, 0xCA);
                var floatBytes = writer.GetSpan(sizeof(float));
                BinaryPrimitives.WriteSingleBigEndian(floatBytes, number);
                writer.Advance(sizeof(float));
                return;
            case double number:
                WriteByte(writer, 0xCB);
                var doubleBytes = writer.GetSpan(sizeof(double));
                BinaryPrimitives.WriteDoubleBigEndian(doubleBytes, number);
                writer.Advance(sizeof(double));
                return;
            case decimal number:
                WriteValue(writer, (double)number);
                return;
            default:
                WriteString(writer, value.ToString() ?? string.Empty);
                return;
        }
    }

    private static void WriteSignedInteger(IBufferWriter<byte> writer, long value)
    {
        if (value is >= 0 and <= 127)
        {
            WriteByte(writer, (byte)value);
        }
        else if (value is >= -32 and < 0)
        {
            WriteByte(writer, unchecked((byte)(sbyte)value));
        }
        else if (value is >= sbyte.MinValue and <= sbyte.MaxValue)
        {
            WriteByte(writer, 0xD0);
            WriteByte(writer, unchecked((byte)(sbyte)value));
        }
        else if (value is >= short.MinValue and <= short.MaxValue)
        {
            WriteInt16(writer, 0xD1, (short)value);
        }
        else if (value is >= int.MinValue and <= int.MaxValue)
        {
            WriteInt32(writer, 0xD2, (int)value);
        }
        else
        {
            WriteByte(writer, 0xD3);
            var bytes = writer.GetSpan(sizeof(long));
            BinaryPrimitives.WriteInt64BigEndian(bytes, value);
            writer.Advance(sizeof(long));
        }
    }

    private static void WriteUnsignedInteger(IBufferWriter<byte> writer, ulong value)
    {
        if (value <= 127)
        {
            WriteByte(writer, (byte)value);
        }
        else if (value <= byte.MaxValue)
        {
            WriteByte(writer, 0xCC);
            WriteByte(writer, (byte)value);
        }
        else if (value <= ushort.MaxValue)
        {
            WriteByte(writer, 0xCD);
            var bytes = writer.GetSpan(sizeof(ushort));
            BinaryPrimitives.WriteUInt16BigEndian(bytes, (ushort)value);
            writer.Advance(sizeof(ushort));
        }
        else if (value <= uint.MaxValue)
        {
            WriteByte(writer, 0xCE);
            var bytes = writer.GetSpan(sizeof(uint));
            BinaryPrimitives.WriteUInt32BigEndian(bytes, (uint)value);
            writer.Advance(sizeof(uint));
        }
        else
        {
            WriteByte(writer, 0xCF);
            var bytes = writer.GetSpan(sizeof(ulong));
            BinaryPrimitives.WriteUInt64BigEndian(bytes, value);
            writer.Advance(sizeof(ulong));
        }
    }

    private static void WriteInt16(IBufferWriter<byte> writer, byte marker, short value)
    {
        WriteByte(writer, marker);
        var bytes = writer.GetSpan(sizeof(short));
        BinaryPrimitives.WriteInt16BigEndian(bytes, value);
        writer.Advance(sizeof(short));
    }

    private static void WriteInt32(IBufferWriter<byte> writer, byte marker, int value)
    {
        WriteByte(writer, marker);
        var bytes = writer.GetSpan(sizeof(int));
        BinaryPrimitives.WriteInt32BigEndian(bytes, value);
        writer.Advance(sizeof(int));
    }

    private static void WriteString(IBufferWriter<byte> writer, string value)
    {
        var byteCount = Encoding.UTF8.GetByteCount(value);
        if (byteCount <= 31)
        {
            WriteByte(writer, (byte)(0xA0 | byteCount));
        }
        else if (byteCount <= byte.MaxValue)
        {
            WriteByte(writer, 0xD9);
            WriteByte(writer, (byte)byteCount);
        }
        else if (byteCount <= ushort.MaxValue)
        {
            WriteByte(writer, 0xDA);
            var lengthBytes = writer.GetSpan(sizeof(ushort));
            BinaryPrimitives.WriteUInt16BigEndian(lengthBytes, (ushort)byteCount);
            writer.Advance(sizeof(ushort));
        }
        else
        {
            WriteByte(writer, 0xDB);
            var lengthBytes = writer.GetSpan(sizeof(uint));
            BinaryPrimitives.WriteUInt32BigEndian(lengthBytes, (uint)byteCount);
            writer.Advance(sizeof(uint));
        }

        var bytes = writer.GetSpan(byteCount);
        Encoding.UTF8.GetBytes(value, bytes);
        writer.Advance(byteCount);
    }

    private static void WriteMapHeader(IBufferWriter<byte> writer, int count)
    {
        if (count <= 15)
        {
            WriteByte(writer, (byte)(0x80 | count));
        }
        else if (count <= ushort.MaxValue)
        {
            WriteByte(writer, 0xDE);
            var bytes = writer.GetSpan(sizeof(ushort));
            BinaryPrimitives.WriteUInt16BigEndian(bytes, (ushort)count);
            writer.Advance(sizeof(ushort));
        }
        else
        {
            WriteByte(writer, 0xDF);
            var bytes = writer.GetSpan(sizeof(uint));
            BinaryPrimitives.WriteUInt32BigEndian(bytes, (uint)count);
            writer.Advance(sizeof(uint));
        }
    }

    private static void WriteArrayHeader(IBufferWriter<byte> writer, int count)
    {
        if (count <= 15)
        {
            WriteByte(writer, (byte)(0x90 | count));
        }
        else if (count <= ushort.MaxValue)
        {
            WriteByte(writer, 0xDC);
            var bytes = writer.GetSpan(sizeof(ushort));
            BinaryPrimitives.WriteUInt16BigEndian(bytes, (ushort)count);
            writer.Advance(sizeof(ushort));
        }
        else
        {
            WriteByte(writer, 0xDD);
            var bytes = writer.GetSpan(sizeof(uint));
            BinaryPrimitives.WriteUInt32BigEndian(bytes, (uint)count);
            writer.Advance(sizeof(uint));
        }
    }

    private static void WriteByte(IBufferWriter<byte> writer, byte value)
    {
        var target = writer.GetSpan(1);
        target[0] = value;
        writer.Advance(1);
    }

    private ref struct Reader(ReadOnlySpan<byte> data)
    {
        private readonly ReadOnlySpan<byte> _data = data;
        private int _offset;

        public object? ReadValue(int depth)
        {
            if (depth > MaximumDepth)
            {
                throw new InvalidDataException("MsgPack 嵌套层级过深。");
            }

            var marker = ReadByte();
            if (marker <= 0x7F)
            {
                return (long)marker;
            }

            if (marker >= 0xE0)
            {
                return (long)unchecked((sbyte)marker);
            }

            if ((marker & 0xE0) == 0xA0)
            {
                return ReadString(marker & 0x1F);
            }

            if ((marker & 0xF0) == 0x90)
            {
                return ReadArray(marker & 0x0F, depth);
            }

            if ((marker & 0xF0) == 0x80)
            {
                return ReadMap(marker & 0x0F, depth);
            }

            return marker switch
            {
                0xC0 => null,
                0xC2 => false,
                0xC3 => true,
                0xCA => ReadSingle(),
                0xCB => ReadDouble(),
                0xCC => (long)ReadByte(),
                0xCD => (long)ReadUInt16(),
                0xCE => (long)ReadUInt32(),
                0xCF => ReadUInt64(),
                0xD0 => (long)ReadSByte(),
                0xD1 => (long)ReadInt16(),
                0xD2 => (long)ReadInt32(),
                0xD3 => ReadInt64(),
                0xD9 => ReadString(ReadByte()),
                0xDA => ReadString(ReadUInt16()),
                0xDB => ReadString(ReadLength32()),
                0xDC => ReadArray(ReadUInt16(), depth),
                0xDD => ReadArray(ReadLength32(), depth),
                0xDE => ReadMap(ReadUInt16(), depth),
                0xDF => ReadMap(ReadLength32(), depth),
                _ => throw new InvalidDataException($"不支持的 MsgPack 标记 0x{marker:X2}。")
            };
        }

        private List<object?> ReadArray(int count, int depth)
        {
            EnsureAvailable(count, 1);
            var values = new List<object?>(count);
            for (var index = 0; index < count; index++)
            {
                values.Add(ReadValue(depth + 1));
            }

            return values;
        }

        private Dictionary<string, object?> ReadMap(int count, int depth)
        {
            EnsureAvailable(count, 2);
            var values = new Dictionary<string, object?>(count, StringComparer.Ordinal);
            for (var index = 0; index < count; index++)
            {
                var key = ReadValue(depth + 1) as string;
                var value = ReadValue(depth + 1);
                if (key is not null)
                {
                    values[key] = value;
                }
            }

            return values;
        }

        private string ReadString(int byteCount)
        {
            var bytes = ReadBytes(byteCount);
            return Encoding.UTF8.GetString(bytes);
        }

        private float ReadSingle()
        {
            var bytes = ReadBytes(sizeof(float));
            return BinaryPrimitives.ReadSingleBigEndian(bytes);
        }

        private double ReadDouble()
        {
            var bytes = ReadBytes(sizeof(double));
            return BinaryPrimitives.ReadDoubleBigEndian(bytes);
        }

        private sbyte ReadSByte() => unchecked((sbyte)ReadByte());
        private short ReadInt16() => BinaryPrimitives.ReadInt16BigEndian(ReadBytes(sizeof(short)));
        private int ReadInt32() => BinaryPrimitives.ReadInt32BigEndian(ReadBytes(sizeof(int)));
        private long ReadInt64() => BinaryPrimitives.ReadInt64BigEndian(ReadBytes(sizeof(long)));
        private ushort ReadUInt16() => BinaryPrimitives.ReadUInt16BigEndian(ReadBytes(sizeof(ushort)));
        private uint ReadUInt32() => BinaryPrimitives.ReadUInt32BigEndian(ReadBytes(sizeof(uint)));
        private ulong ReadUInt64() => BinaryPrimitives.ReadUInt64BigEndian(ReadBytes(sizeof(ulong)));

        private int ReadLength32()
        {
            var value = ReadUInt32();
            if (value > int.MaxValue)
            {
                throw new InvalidDataException("MsgPack 长度字段超出 Int32 范围。");
            }

            return (int)value;
        }

        private byte ReadByte()
        {
            EnsureAvailable(1, 1);
            return _data[_offset++];
        }

        private ReadOnlySpan<byte> ReadBytes(int count)
        {
            EnsureAvailable(count, 1);
            var bytes = _data.Slice(_offset, count);
            _offset += count;
            return bytes;
        }

        private void EnsureAvailable(int count, int minimumBytesPerItem)
        {
            if (count < 0 ||
                count > (_data.Length - _offset) / minimumBytesPerItem)
            {
                throw new InvalidDataException("MsgPack 数据被截断或声明长度无效。");
            }
        }
    }
}
