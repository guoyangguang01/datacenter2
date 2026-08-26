package com.sdncustom.protocol.modbus;

/**
 * Modbus 功能码
 */
public final class ModbusFunction {

    /** 读线圈 */
    public static final byte READ_COILS = 0x01;

    /** 读离散输入 */
    public static final byte READ_DISCRETE_INPUTS = 0x02;

    /** 读保持寄存器 */
    public static final byte READ_HOLDING_REGISTERS = 0x03;

    /** 读输入寄存器 */
    public static final byte READ_INPUT_REGISTERS = 0x04;

    /** 写单个线圈 */
    public static final byte WRITE_SINGLE_COIL = 0x05;

    /** 写单个寄存器 */
    public static final byte WRITE_SINGLE_REGISTER = 0x06;

    /** 写多个线圈 */
    public static final byte WRITE_MULTIPLE_COILS = 0x0F;

    /** 写多个寄存器 */
    public static final byte WRITE_MULTIPLE_REGISTERS = 0x10;

    private ModbusFunction() {
    }
}
