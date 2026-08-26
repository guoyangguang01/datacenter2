package com.sdncustom.protocol.tcp;

/**
 * 自定义 TCP 协议命令码
 */
public final class TcpCommand {

    /** 读取测点值请求 */
    public static final byte READ_REQUEST = 0x01;

    /** 读取测点值响应 */
    public static final byte READ_RESPONSE = 0x02;

    /** 写入测点值请求 */
    public static final byte WRITE_REQUEST = 0x03;

    /** 写入测点值响应 */
    public static final byte WRITE_RESPONSE = 0x04;

    /** 测点值变化推送 */
    public static final byte VALUE_PUSH = 0x05;

    /** 心跳请求 */
    public static final byte HEARTBEAT_REQUEST = 0x10;

    /** 心跳响应 */
    public static final byte HEARTBEAT_RESPONSE = 0x11;

    private TcpCommand() {
    }
}
