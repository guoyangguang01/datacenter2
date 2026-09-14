package com.sdncustom.protocol.modbus;

import com.sdncustom.common.model.enums.PointDataType;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 多寄存器（32/64 位）值的寄存器编解码。
 *
 * Modbus 对多寄存器的字序没有标准，现场设备两种都常见：
 * - big / ABCD（高字在前，默认）：寄存器 N 放高 16 位
 * - little / CDAB（低字在前，字交换）：寄存器 N 放低 16 位
 * 字内字节固定为大端（Modbus 规定），只交换寄存器顺序。
 *
 * 数值一律视为「一组连续寄存器」，起始地址取测点 address 解析出的寄存器地址。
 */
final class ModbusRegisters {

    private ModbusRegisters() {
    }

    /** 该数据类型占用的寄存器个数；不支持跨寄存器的类型按 1 处理 */
    static int width(PointDataType type) {
        if (type == null) {
            return 1;
        }
        return switch (type) {
            case INT32, FLOAT32 -> 2;
            case FLOAT64 -> 4;
            default -> 1;
        };
    }

    /** 是否为跨寄存器（32/64 位）类型 */
    static boolean isMultiRegister(PointDataType type) {
        return width(type) > 1;
    }

    /**
     * 编码为寄存器序列。仅支持 INT32/FLOAT32/FLOAT64。
     *
     * @param lowWordFirst true 表示低字在前（CDAB），false 为高字在前（ABCD）
     */
    static int[] encode(double value, PointDataType type, boolean lowWordFirst) {
        ByteBuffer buffer = ByteBuffer.allocate(width(type) * 2);
        switch (type) {
            case INT32 -> buffer.putInt((int) value);
            case FLOAT32 -> buffer.putFloat((float) value);
            case FLOAT64 -> buffer.putDouble(value);
            default -> throw new IllegalArgumentException("Not a multi-register type: " + type);
        }
        return toWords(buffer.array(), lowWordFirst);
    }

    /**
     * 从寄存器序列解码。寄存器不足返回 null（由调用方按读取失败处理）。
     * 非跨寄存器类型也支持：INT16 返回 Short。
     */
    static Object decode(int[] registers, PointDataType type, boolean lowWordFirst) {
        if (registers == null) {
            return null;
        }
        int width = width(type);
        if (registers.length < width) {
            return null;
        }
        if (type == PointDataType.INT16) {
            return (short) (registers[0] & 0xFFFF);
        }
        if (!isMultiRegister(type)) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(toBytes(registers, width, lowWordFirst));
        return switch (type) {
            case INT32 -> buffer.getInt();
            case FLOAT32 -> buffer.getFloat();
            case FLOAT64 -> buffer.getDouble();
            default -> null;
        };
    }

    /** 大端字节流 → 寄存器字；低字在前时反转字序 */
    private static int[] toWords(byte[] bytes, boolean lowWordFirst) {
        int words = bytes.length / 2;
        int[] registers = new int[words];
        for (int i = 0; i < words; i++) {
            registers[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }
        if (lowWordFirst) {
            reverse(registers);
        }
        return registers;
    }

    /** 寄存器字 → 大端字节流；低字在前时先反转回高字在前 */
    private static byte[] toBytes(int[] registers, int width, boolean lowWordFirst) {
        int[] words = Arrays.copyOf(registers, width);
        if (lowWordFirst) {
            reverse(words);
        }
        byte[] bytes = new byte[width * 2];
        for (int i = 0; i < width; i++) {
            bytes[i * 2] = (byte) (words[i] >> 8);
            bytes[i * 2 + 1] = (byte) words[i];
        }
        return bytes;
    }

    private static void reverse(int[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }
}
