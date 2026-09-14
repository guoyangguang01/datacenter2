package com.sdncustom.protocol.modbus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.protocol.ProtocolAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modbus TCP 协议适配器
 *
 * 地址格式:
 * - 保持寄存器: 40001-49999 (功能码 0x03)
 * - 输入寄存器: 30001-39999 (功能码 0x04)
 * - 线圈: 00001-09999 (功能码 0x01)
 * - 离散输入: 10001-19999 (功能码 0x02)
 *
 * 多寄存器类型（INT32/FLOAT32 占 2 个、FLOAT64 占 4 个连续寄存器）以测点 address
 * 为起始寄存器；写侧走功能码 0x10。字序由通道 connectionConfig 的 wordOrder 决定：
 * {@code big}（默认，高字在前 ABCD）/ {@code little}（低字在前 CDAB）。
 */
@Slf4j
public class ModbusTcpAdapter implements ProtocolAdapter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModbusTcpClient client = new ModbusTcpClient();
    private Channel channel;
    private volatile boolean connected = false;
    /** 多寄存器值是否低字在前（connectionConfig.wordOrder = little） */
    private volatile boolean lowWordFirst = false;

    @Override
    public void connect(Channel channel) {
        this.channel = channel;
        try {
            Map<String, Object> config = objectMapper.readValue(channel.getConnectionConfig(), Map.class);
            String host = (String) config.get("host");
            int port = config.containsKey("port") ? (int) config.get("port") : 502;
            int unitId = config.containsKey("unitId") ? (int) config.get("unitId") : 1;
            String wordOrder = config.containsKey("wordOrder") ? String.valueOf(config.get("wordOrder")) : "big";
            this.lowWordFirst = "little".equalsIgnoreCase(wordOrder);

            client.setUnitId(unitId);
            client.connect(host, port);
            connected = true;
            log.info("Connected to Modbus TCP server: {}:{}", host, port);
        } catch (Exception e) {
            connected = false;
            // 清理可能已创建的连接资源
            client.disconnect();
            log.error("Failed to connect to Modbus TCP server: {}", e.getMessage());
            throw new RuntimeException("Modbus connection failed", e);
        }
    }

    @Override
    public void disconnect() {
        connected = false;
        client.disconnect();
    }

    @Override
    public PointValue readPoint(MeasurementPoint point) {
        try {
            List<PointValue> result = readPoints(List.of(point));
            return result.isEmpty() ? commLostValue(point) : result.get(0);
        } catch (Exception e) {
            log.error("Failed to read Modbus point: {}", point.getPointId(), e);
            return commLostValue(point);
        }
    }

    @Override
    public void writePoint(MeasurementPoint point, Object value) {
        if (!connected) {
            throw new RuntimeException("Not connected");
        }
        try {
            ModbusAddress addr = parseAddress(point.getAddress());

            if (addr.type == AddressType.COIL) {
                client.writeSingleCoil(addr.registerAddress, asBoolean(value));
                return;
            }
            if (addr.type != AddressType.HOLDING_REGISTER) {
                throw new RuntimeException("Cannot write to address type: " + addr.type);
            }

            if (ModbusRegisters.isMultiRegister(point.getDataType())) {
                // 32/64 位：一次写多个连续寄存器（功能码 0x10）
                int[] registers = ModbusRegisters.encode(asDouble(value), point.getDataType(), lowWordFirst);
                client.writeMultipleRegisters(addr.registerAddress, registers);
            } else {
                client.writeSingleRegister(addr.registerAddress, convertToInt(value, point.getDataType()));
            }
        } catch (Exception e) {
            log.error("Failed to write Modbus point: {}", point.getPointId(), e);
            throw new RuntimeException("Modbus write failed", e);
        }
    }

    @Override
    public List<PointValue> readPoints(List<MeasurementPoint> points) {
        if (!connected) {
            throw new RuntimeException("Not connected");
        }

        List<PointValue> results = new ArrayList<>();
        for (MeasurementPoint point : points) {
            try {
                PointValue pv = readSinglePoint(point);
                if (pv != null) {
                    results.add(pv);
                }
            } catch (Exception e) {
                log.error("Failed to read Modbus point: {}", point.getPointId(), e);
                PointValue pv = new PointValue();
                pv.setPointId(point.getPointId());
                pv.setQuality(PointQuality.BAD);
                pv.setTimestamp(System.currentTimeMillis());
                pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
                results.add(pv);
                // 读失败说明链路可能已断：关掉客户端让 isConnected() 如实反映
                //（Modbus 的 socket.isConnected() 只表示"曾经连过"，不会因为对端消失而变 false），
                // 否则上层一直以为通道还连着（"假连接"），重连无从触发
                client.disconnect();
                break;
            }
        }
        return results;
    }

    @Override
    public boolean isConnected() {
        return connected && client.isConnected();
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

    private PointValue readSinglePoint(MeasurementPoint point) throws Exception {
        ModbusAddress addr = parseAddress(point.getAddress());
        PointDataType dataType = point.getDataType();
        int width = ModbusRegisters.width(dataType);
        Object value;
        PointQuality quality = PointQuality.GOOD;

        switch (addr.type) {
            case HOLDING_REGISTER: {
                int[] regs = client.readHoldingRegisters(addr.registerAddress, width);
                value = decodeRegisters(regs, dataType);
                break;
            }
            case INPUT_REGISTER: {
                int[] regs = client.readInputRegisters(addr.registerAddress, width);
                value = decodeRegisters(regs, dataType);
                break;
            }
            case COIL: {
                boolean[] coils = client.readCoils(addr.registerAddress, 1);
                value = coils[0];
                break;
            }
            case DISCRETE_INPUT: {
                boolean[] discreteInputs = client.readDiscreteInputs(addr.registerAddress, 1);
                value = discreteInputs[0];
                break;
            }
            default:
                log.warn("Unsupported Modbus address type: {}", addr.type);
                value = null;
                quality = PointQuality.BAD;
        }

        if (quality == PointQuality.GOOD && value != null) {
            value = convertValue(value, dataType);
        }

        PointValue pv = new PointValue();
        pv.setPointId(point.getPointId());
        pv.setValue(value);
        pv.setQuality(quality);
        pv.setTimestamp(System.currentTimeMillis());
        pv.setSourceChannelId(channel != null ? channel.getChannelId() : null);
        return pv;
    }

    /** 多寄存器类型按字序解码；其余（BOOL/STRING/INT16）落到单寄存器转换 */
    private Object decodeRegisters(int[] registers, PointDataType dataType) {
        if (ModbusRegisters.isMultiRegister(dataType)) {
            Object decoded = ModbusRegisters.decode(registers, dataType, lowWordFirst);
            if (decoded != null) {
                return decoded;
            }
        }
        return registers[0];
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot write non-numeric value: " + value);
        }
    }

    private ModbusAddress parseAddress(String address) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be empty");
        }

        // 标准 Modbus 地址格式: 40001, 30001, 00001, 10001
        int addr;
        try {
            addr = Integer.parseInt(address);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Modbus address: " + address);
        }

        if (addr >= 40001 && addr <= 49999) {
            return new ModbusAddress(AddressType.HOLDING_REGISTER, addr - 40001);
        } else if (addr >= 30001 && addr <= 39999) {
            return new ModbusAddress(AddressType.INPUT_REGISTER, addr - 30001);
        } else if (addr >= 1 && addr <= 9999) {
            return new ModbusAddress(AddressType.COIL, addr - 1);
        } else if (addr >= 10001 && addr <= 19999) {
            return new ModbusAddress(AddressType.DISCRETE_INPUT, addr - 10001);
        } else {
            // 直接地址，假设为保持寄存器
            return new ModbusAddress(AddressType.HOLDING_REGISTER, addr);
        }
    }

    private Object convertValue(Object value, PointDataType dataType) {
        if (value == null) return null;
        double numValue = value instanceof Number ? ((Number) value).doubleValue() : 0;
        switch (dataType) {
            case BOOL:
                return value instanceof Boolean ? value : numValue != 0;
            case INT16:
                return (short) numValue;
            case INT32:
                return (int) numValue;
            case FLOAT32:
                return (float) numValue;
            case FLOAT64:
                return numValue;
            case STRING:
                return String.valueOf(value);
            default:
                return value;
        }
    }

    private int convertToInt(Object value, PointDataType dataType) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? 1 : 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private enum AddressType {
        HOLDING_REGISTER,
        INPUT_REGISTER,
        COIL,
        DISCRETE_INPUT
    }

    private static class ModbusAddress {
        final AddressType type;
        final int registerAddress;

        ModbusAddress(AddressType type, int registerAddress) {
            this.type = type;
            this.registerAddress = registerAddress;
        }
    }
}
