package com.sdncustom.protocol.tcp;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * TCP 协议消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TcpMessage {

    private byte command;
    private String body;

    /**
     * 编码为字节数组
     * 格式: [Length(4B)] [Command(1B)] [JSON Body]
     */
    public byte[] encode() {
        byte[] bodyBytes = body != null ? body.getBytes() : new byte[0];
        int length = 1 + bodyBytes.length; // command + body

        byte[] result = new byte[4 + length];
        // Length (大端序)
        result[0] = (byte) (length >> 24);
        result[1] = (byte) (length >> 16);
        result[2] = (byte) (length >> 8);
        result[3] = (byte) length;
        // Command
        result[4] = command;
        // Body
        System.arraycopy(bodyBytes, 0, result, 5, bodyBytes.length);

        return result;
    }

    /**
     * 从字节数组解码
     */
    public static TcpMessage decode(byte[] data) {
        if (data.length < 4) {
            throw new IllegalArgumentException("Invalid message: too short");
        }

        int length = ((data[0] & 0xFF) << 24) |
                     ((data[1] & 0xFF) << 16) |
                     ((data[2] & 0xFF) << 8) |
                     (data[3] & 0xFF);

        if (data.length < 4 + length) {
            throw new IllegalArgumentException("Invalid message: incomplete data");
        }

        byte command = data[4];
        String body = length > 1 ? new String(data, 5, length - 1) : "";

        return new TcpMessage(command, body);
    }
}
