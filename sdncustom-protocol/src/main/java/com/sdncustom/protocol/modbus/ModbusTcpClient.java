package com.sdncustom.protocol.modbus;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modbus TCP 客户端
 */
@Slf4j
public class ModbusTcpClient {

    private static final int MODBUS_TCP_PORT = 502;
    private static final int MBAP_HEADER_SIZE = 7;
    private static final int DEFAULT_TIMEOUT = 5000;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private final AtomicInteger transactionId = new AtomicInteger(0);
    private int unitId = 1;

    // 串行化请求/响应交换，防止并发调用帧交错
    private final Object lock = new Object();

    /**
     * 连接到 Modbus TCP 服务器
     */
    public void connect(String host, int port) throws IOException {
        try {
            socket = new Socket(host, port > 0 ? port : MODBUS_TCP_PORT);
            socket.setSoTimeout(DEFAULT_TIMEOUT);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            log.info("Connected to Modbus TCP server: {}:{}", host, port);
        } catch (IOException e) {
            // 连接失败时清理已创建的 socket 资源
            disconnect();
            throw e;
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
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
                log.info("Modbus TCP connection closed");
            }
        } catch (IOException e) {
            log.error("Error closing Modbus connection", e);
        } finally {
            socket = null;
            input = null;
            output = null;
        }
    }

    /**
     * 是否已连接
     */
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    /**
     * 设置 Unit ID
     */
    public void setUnitId(int unitId) {
        this.unitId = unitId;
    }

    /**
     * 读保持寄存器
     *
     * @param startAddress 起始地址
     * @param quantity     数量
     * @return 寄存器值数组
     */
    public int[] readHoldingRegisters(int startAddress, int quantity) throws IOException {
        byte[] request = buildReadRequest(ModbusFunction.READ_HOLDING_REGISTERS, startAddress, quantity);
        byte[] response = sendRequest(request);
        return parseReadResponse(response, quantity);
    }

    /**
     * 读输入寄存器
     */
    public int[] readInputRegisters(int startAddress, int quantity) throws IOException {
        byte[] request = buildReadRequest(ModbusFunction.READ_INPUT_REGISTERS, startAddress, quantity);
        byte[] response = sendRequest(request);
        return parseReadResponse(response, quantity);
    }

    /**
     * 写单个寄存器
     *
     * @param address 寄存器地址
     * @param value   要写入的值
     */
    public void writeSingleRegister(int address, int value) throws IOException {
        byte[] request = buildWriteSingleRequest(address, value);
        byte[] response = sendRequest(request);
        parseWriteResponse(response);
    }

    /**
     * 读线圈
     */
    public boolean[] readCoils(int startAddress, int quantity) throws IOException {
        byte[] request = buildReadRequest(ModbusFunction.READ_COILS, startAddress, quantity);
        byte[] response = sendRequest(request);
        return parseCoilResponse(response, quantity);
    }

    /**
     * 读离散输入
     */
    public boolean[] readDiscreteInputs(int startAddress, int quantity) throws IOException {
        byte[] request = buildReadRequest(ModbusFunction.READ_DISCRETE_INPUTS, startAddress, quantity);
        byte[] response = sendRequest(request);
        return parseCoilResponse(response, quantity);
    }

    /**
     * 写单个线圈
     */
    public void writeSingleCoil(int address, boolean value) throws IOException {
        byte[] request = new byte[MBAP_HEADER_SIZE + 5];
        buildMbapHeader(request, 5);
        request[MBAP_HEADER_SIZE] = ModbusFunction.WRITE_SINGLE_COIL;
        request[MBAP_HEADER_SIZE + 1] = (byte) (address >> 8);
        request[MBAP_HEADER_SIZE + 2] = (byte) address;
        request[MBAP_HEADER_SIZE + 3] = (byte) (value ? 0xFF : 0x00);
        request[MBAP_HEADER_SIZE + 4] = 0x00;
        byte[] response = sendRequest(request);
        parseWriteResponse(response);
    }

    private byte[] buildReadRequest(byte functionCode, int startAddress, int quantity) {
        byte[] request = new byte[MBAP_HEADER_SIZE + 5];
        buildMbapHeader(request, 5);
        request[MBAP_HEADER_SIZE] = functionCode;
        request[MBAP_HEADER_SIZE + 1] = (byte) (startAddress >> 8);
        request[MBAP_HEADER_SIZE + 2] = (byte) startAddress;
        request[MBAP_HEADER_SIZE + 3] = (byte) (quantity >> 8);
        request[MBAP_HEADER_SIZE + 4] = (byte) quantity;
        return request;
    }

    private byte[] buildWriteSingleRequest(int address, int value) {
        byte[] request = new byte[MBAP_HEADER_SIZE + 5];
        buildMbapHeader(request, 5);
        request[MBAP_HEADER_SIZE] = ModbusFunction.WRITE_SINGLE_REGISTER;
        request[MBAP_HEADER_SIZE + 1] = (byte) (address >> 8);
        request[MBAP_HEADER_SIZE + 2] = (byte) address;
        request[MBAP_HEADER_SIZE + 3] = (byte) (value >> 8);
        request[MBAP_HEADER_SIZE + 4] = (byte) value;
        return request;
    }

    private void buildMbapHeader(byte[] data, int pduLength) {
        int tid = transactionId.getAndIncrement() & 0xFFFF;
        data[0] = (byte) (tid >> 8);
        data[1] = (byte) tid;
        data[2] = 0; // Protocol ID
        data[3] = 0;
        int length = pduLength + 1; // +1 for unit ID
        data[4] = (byte) (length >> 8);
        data[5] = (byte) length;
        data[6] = (byte) unitId;
    }

    private byte[] sendRequest(byte[] request) throws IOException {
        synchronized (lock) {
            int expectedTid = ((request[0] & 0xFF) << 8) | (request[1] & 0xFF);

            output.write(request);
            output.flush();

            // 读取 MBAP Header
            byte[] header = input.readNBytes(MBAP_HEADER_SIZE);
            if (header.length < MBAP_HEADER_SIZE) {
                throw new IOException("Incomplete MBAP header");
            }

            int transactionId = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
            int protocolId = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);

            // 校验 Protocol ID 必须为 0
            if (protocolId != 0) {
                throw new IOException("Invalid Modbus protocol ID: " + protocolId);
            }
            // 校验 Transaction ID 与请求一致，避免接受陈旧/跨线程的响应
            if (transactionId != expectedTid) {
                log.error("Modbus transaction ID mismatch: expected={}, got={}", expectedTid, transactionId);
                throw new IOException("Modbus transaction ID mismatch: expected=" + expectedTid + ", got=" + transactionId);
            }
            // 校验 MBAP 长度（unit id + PDU），防止 readNBytes(负数)
            if (length < 2) {
                throw new IOException("Invalid Modbus MBAP length: " + length);
            }

            byte[] pdu = input.readNBytes(length - 1); // -1 for unit ID already in header
            if (pdu.length < length - 1) {
                throw new IOException("Incomplete PDU");
            }

            // 检查错误响应
            if ((pdu[0] & 0x80) != 0) {
                throw new IOException("Modbus error response: function=" + Integer.toHexString(pdu[0] & 0xFF) +
                        ", exception=" + (pdu.length > 1 ? pdu[1] : "unknown"));
            }

            return pdu;
        }
    }

    private int[] parseReadResponse(byte[] response, int quantity) {
        if (response.length < 3) {
            throw new RuntimeException("Invalid read response");
        }

        int byteCount = response[1] & 0xFF;
        int[] values = new int[quantity];

        for (int i = 0; i < quantity; i++) {
            int offset = 2 + i * 2;
            if (offset + 1 < response.length) {
                values[i] = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
            }
        }

        return values;
    }

    private boolean[] parseCoilResponse(byte[] response, int quantity) {
        if (response.length < 3) {
            throw new RuntimeException("Invalid coil response");
        }

        int byteCount = response[1] & 0xFF;
        boolean[] values = new boolean[quantity];

        for (int i = 0; i < quantity; i++) {
            int byteIndex = 2 + i / 8;
            int bitIndex = i % 8;
            if (byteIndex < response.length) {
                values[i] = ((response[byteIndex] >> bitIndex) & 1) == 1;
            }
        }

        return values;
    }

    private void parseWriteResponse(byte[] response) {
        if (response.length < 5) {
            throw new RuntimeException("Invalid write response");
        }
        // 写响应通常包含回显的地址和值，无需额外解析
    }
}
