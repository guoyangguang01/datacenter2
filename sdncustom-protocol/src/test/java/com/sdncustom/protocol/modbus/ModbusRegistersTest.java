package com.sdncustom.protocol.modbus;

import com.sdncustom.common.model.enums.PointDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModbusRegisters 多寄存器编解码测试")
class ModbusRegistersTest {

    @Test
    @DisplayName("寄存器宽度：16位/BOOL 占 1，32位占 2，FLOAT64 占 4")
    void width() {
        assertEquals(1, ModbusRegisters.width(PointDataType.BOOL));
        assertEquals(1, ModbusRegisters.width(PointDataType.INT16));
        assertEquals(2, ModbusRegisters.width(PointDataType.INT32));
        assertEquals(2, ModbusRegisters.width(PointDataType.FLOAT32));
        assertEquals(4, ModbusRegisters.width(PointDataType.FLOAT64));
        assertEquals(1, ModbusRegisters.width(null));
    }

    @Test
    @DisplayName("FLOAT32 高字在前：25.6f -> [0x41CC, 0xCCCD]")
    void encodeFloat32HighWordFirst() {
        assertArrayEquals(new int[]{0x41CC, 0xCCCD},
                ModbusRegisters.encode(25.6f, PointDataType.FLOAT32, false));
    }

    @Test
    @DisplayName("FLOAT32 低字在前：字序反转 -> [0xCCCD, 0x41CC]")
    void encodeFloat32LowWordFirst() {
        assertArrayEquals(new int[]{0xCCCD, 0x41CC},
                ModbusRegisters.encode(25.6f, PointDataType.FLOAT32, true));
    }

    @Test
    @DisplayName("INT32 编解码往返（两种字序）")
    void int32RoundTrip() {
        for (boolean lowWordFirst : new boolean[]{false, true}) {
            int[] registers = ModbusRegisters.encode(-123456, PointDataType.INT32, lowWordFirst);
            assertEquals(2, registers.length);
            Object decoded = ModbusRegisters.decode(registers, PointDataType.INT32, lowWordFirst);
            assertEquals(-123456, ((Number) decoded).intValue());
        }
    }

    @Test
    @DisplayName("FLOAT32 编解码往返（两种字序）")
    void float32RoundTrip() {
        for (boolean lowWordFirst : new boolean[]{false, true}) {
            int[] registers = ModbusRegisters.encode(25.6, PointDataType.FLOAT32, lowWordFirst);
            Object decoded = ModbusRegisters.decode(registers, PointDataType.FLOAT32, lowWordFirst);
            assertEquals(25.6f, ((Number) decoded).floatValue(), 1e-5f);
        }
    }

    @Test
    @DisplayName("FLOAT64 占 4 个寄存器，编解码往返（两种字序）")
    void float64RoundTrip() {
        for (boolean lowWordFirst : new boolean[]{false, true}) {
            int[] registers = ModbusRegisters.encode(3.14159265, PointDataType.FLOAT64, lowWordFirst);
            assertEquals(4, registers.length);
            Object decoded = ModbusRegisters.decode(registers, PointDataType.FLOAT64, lowWordFirst);
            assertEquals(3.14159265, ((Number) decoded).doubleValue(), 1e-12);
        }
    }

    @Test
    @DisplayName("低字在前时寄存器内容确实与高字在前不同")
    void wordOrderActuallySwaps() {
        int[] big = ModbusRegisters.encode(25.6, PointDataType.FLOAT32, false);
        int[] little = ModbusRegisters.encode(25.6, PointDataType.FLOAT32, true);
        assertEquals(big[0], little[1]);
        assertEquals(big[1], little[0]);
    }

    @Test
    @DisplayName("INT16 解码为 Short（负数按补码）")
    void decodeInt16() {
        Object decoded = ModbusRegisters.decode(new int[]{0xFFFF}, PointDataType.INT16, false);
        assertEquals((short) -1, (short) ((Number) decoded).shortValue());
    }

    @Test
    @DisplayName("寄存器不足返回 null，不返回半个值")
    void decodeInsufficientRegisters() {
        assertNull(ModbusRegisters.decode(new int[]{0x41CC}, PointDataType.FLOAT32, false));
        assertNull(ModbusRegisters.decode(new int[]{1, 2}, PointDataType.FLOAT64, false));
        assertNull(ModbusRegisters.decode(null, PointDataType.FLOAT32, false));
    }

    @Test
    @DisplayName("BOOL/STRING 不是多寄存器类型；对它们编码应报错而不是静默截断")
    void nonMultiRegisterTypes() {
        assertFalse(ModbusRegisters.isMultiRegister(PointDataType.BOOL));
        assertFalse(ModbusRegisters.isMultiRegister(PointDataType.STRING));
        assertNull(ModbusRegisters.decode(new int[]{1}, PointDataType.BOOL, false));
        assertThrows(IllegalArgumentException.class,
                () -> ModbusRegisters.encode(1, PointDataType.INT16, false));
    }
}
