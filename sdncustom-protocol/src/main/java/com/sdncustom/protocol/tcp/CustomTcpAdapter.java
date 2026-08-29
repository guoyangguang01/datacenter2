package com.sdncustom.protocol.tcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 自定义 TCP 协议适配器
 */
@Slf4j
public class CustomTcpAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;
    private Channel channel;
    private volatile boolean connected = false;

    // 串行化请求/响应交换，防止并发读写帧交错
    private final Object lock = new Object();

    @Override
    public void connect(Channel channel) {
        this.channel = channel;
        try {
            Map<String, Object> config = objectMapper.readValue(channel.getConnectionConfig(), Map.class);
            String host = (String) config.get("host");
            int port = (int) config.get("port");

            socket = new Socket(host, port);
            socket.setSoTimeout(5000);

            // 验证 socket 是否真正建立连接
            if (socket.isClosed() || !socket.isConnected()) {
                throw new IOException("Socket created but not connected");
            }

            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            connected = true;

            log.info("Connected to TCP server: {}:{}", host, port);
        } catch (Exception e) {
            connected = false;
            // 清理可能已创建的 socket 资源，防止泄漏
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
            socket = null;
            input = null;
            output = null;
            log.error("Failed to connect to TCP server: {}", e.getMessage());
            throw new RuntimeException("Connection failed", e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                // 先 shutdownInput/Output 中断阻塞的读写操作，再 close 释放资源
                // 这样可以确保正在 readNBytes() 阻塞的线程被中断
                try {
                    socket.shutdownInput();
                } catch (IOException ignored) {
                    // socket 可能已经关闭，忽略
                }
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                    // socket 可能已经关闭，忽略
                }
                socket.close();
                log.info("TCP socket closed for channel: {}", channel != null ? channel.getChannelId() : "unknown");
            }
        } catch (IOException e) {
            log.error("Error closing socket for channel: {}", channel != null ? channel.getChannelId() : "unknown", e);
        } finally {
            socket = null;
            input = null;
            output = null;
        }
    }

    @Override
    public PointValue readPoint(MeasurementPoint point) {
        try {
            List<PointValue> result = readPoints(List.of(point));
            return result.isEmpty() ? commLostValue(point) : result.get(0);
        } catch (Exception e) {
            log.error("Failed to read point: {}", point.getPointId(), e);
            return commLostValue(point);
        }
    }

    @Override
    public void writePoint(MeasurementPoint point, Object value) {
        if (!connected) {
            throw new RuntimeException("Not connected");
        }
        synchronized (lock) {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("pointId", point.getPointId());
                body.put("value", value);

                TcpMessage request = new TcpMessage(TcpCommand.WRITE_REQUEST, objectMapper.writeValueAsString(body));
                send(request);

                TcpMessage response = receive();
                if (response.getCommand() != TcpCommand.WRITE_RESPONSE) {
                    throw new RuntimeException("Unexpected response command: " + response.getCommand());
                }
            } catch (Exception e) {
                log.error("Failed to write point: {}", point.getPointId(), e);
                throw new RuntimeException("Write failed", e);
            }
        }
    }

    @Override
    public List<PointValue> readPoints(List<MeasurementPoint> points) {
        if (!connected) {
            throw new RuntimeException("Not connected");
        }
        synchronized (lock) {
            try {
                List<String> pointIds = points.stream().map(MeasurementPoint::getPointId).toList();
                Map<String, Object> body = new HashMap<>();
                body.put("pointIds", pointIds);

                TcpMessage request = new TcpMessage(TcpCommand.READ_REQUEST, objectMapper.writeValueAsString(body));
                send(request);

                TcpMessage response = receive();
                if (response.getCommand() != TcpCommand.READ_RESPONSE) {
                    throw new RuntimeException("Unexpected response command: " + response.getCommand());
                }

                Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
                List<Map<String, Object>> values = (List<Map<String, Object>>) responseBody.get("values");

                List<PointValue> result = new ArrayList<>();
                if (values != null) {
                    for (Map<String, Object> v : values) {
                        PointValue pv = new PointValue();
                        pv.setPointId((String) v.get("pointId"));
                        pv.setValue(v.get("value"));
                        pv.setQuality(PointQuality.valueOf((String) v.getOrDefault("quality", "GOOD")));
                        pv.setTimestamp(parseTimestamp(v.getOrDefault("timestamp", System.currentTimeMillis())));
                        pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
                        result.add(pv);
                    }
                }
                return result;
            } catch (Exception e) {
                log.error("Failed to read points", e);
                throw new RuntimeException("Read failed", e);
            }
        }
    }

    /**
     * 解析设备返回的 timestamp，兼容 Number 与 String 类型
     */
    private long parseTimestamp(Object ts) {
        if (ts instanceof Number) {
            return ((Number) ts).longValue();
        }
        if (ts instanceof String) {
            try {
                return Long.parseLong((String) ts);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse timestamp string: {}", ts);
            }
        }
        return System.currentTimeMillis();
    }

    @Override
    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    private void send(TcpMessage message) throws IOException {
        byte[] data = message.encode();
        output.write(data);
        output.flush();
    }

    private TcpMessage receive() throws IOException {
        // 读取长度 (4 bytes)
        byte[] lengthBytes = input.readNBytes(4);
        if (lengthBytes.length < 4) {
            throw new IOException("Connection closed");
        }
        int length = ((lengthBytes[0] & 0xFF) << 24) |
                     ((lengthBytes[1] & 0xFF) << 16) |
                     ((lengthBytes[2] & 0xFF) << 8) |
                     (lengthBytes[3] & 0xFF);

        // 校验帧长度，防止非法长度导致崩溃或阻塞（readNBytes(负数) / data[0] on empty）
        if (length <= 0) {
            log.error("Invalid TCP frame length: {} (<= 0)", length);
            closeConnection();
            throw new IOException("Invalid frame length: " + length);
        }
        if (length > 65535) {
            log.error("Invalid TCP frame length: {} (> 65535)", length);
            closeConnection();
            throw new IOException("Invalid frame length: " + length);
        }

        // 读取 command + body
        byte[] data = input.readNBytes(length);
        if (data.length < length) {
            throw new IOException("Incomplete message");
        }

        byte command = data[0];
        String body = length > 1 ? new String(data, 1, length - 1, StandardCharsets.UTF_8) : "";

        return new TcpMessage(command, body);
    }

    /**
     * 关闭底层连接并清理状态，用于协议错误等场景
     */
    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.shutdownInput();
                } catch (IOException ignored) {
                }
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                }
                socket.close();
            }
        } catch (IOException e) {
            log.debug("Error closing socket", e);
        } finally {
            socket = null;
            input = null;
            output = null;
            connected = false;
        }
    }

    private PointValue commLostValue(MeasurementPoint point) {
        PointValue pv = new PointValue();
        pv.setPointId(point.getPointId());
        pv.setValue(null);
        pv.setQuality(PointQuality.COMM_LOST);
        pv.setTimestamp(System.currentTimeMillis());
        pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
        return pv;
    }
}
