package com.sdncustom.server.config;

import com.sdncustom.common.dto.BusinessSystemDTO;
import com.sdncustom.common.dto.ChannelDTO;
import com.sdncustom.common.dto.MeasurementPointDTO;
import com.sdncustom.common.dto.PointSourceDTO;
import com.sdncustom.common.model.enums.ChannelDirection;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.server.repository.ChannelRepository;
import com.sdncustom.server.repository.MeasurementPointRepository;
import com.sdncustom.server.service.BusinessSystemService;
import com.sdncustom.server.service.ChannelService;
import com.sdncustom.server.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 示例数据播种（仅空库首次启动）：预置两个示例业务、四个通道、六个测点，
 * 便于开箱即体验多业务隔离。全部通道 autoConnect=false，不依赖模拟器；
 * 库里已有通道或测点则跳过，不覆盖任何真实数据。失败仅告警，不阻断启动。
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {

    private final ChannelRepository channelRepository;
    private final MeasurementPointRepository pointRepository;
    private final BusinessSystemService businessSystemService;
    private final ChannelService channelService;
    private final PointService pointService;

    @Override
    public void run(String... args) {
        try {
            if (channelRepository.count() > 0 || pointRepository.count() > 0) {
                log.info("DemoDataInitializer: existing data present, skip seeding");
                return;
            }
            seedBusinesses();
            seedChannels();
            seedPoints();
            log.info("DemoDataInitializer: seeded 2 businesses, 4 channels, 6 points (all autoConnect=false)");
        } catch (Exception e) {
            log.error("DemoDataInitializer failed, continuing startup", e);
        }
    }

    private void seedBusinesses() {
        businessSystemService.create(business("factory_1", "一号工厂", "装配线数据采集示例"));
        businessSystemService.create(business("factory_2", "二号工厂", "环境监控示例"));
    }

    private void seedChannels() {
        channelService.create(channel("f1_tcp", "factory_1", "一号厂-装配线TCP",
                ProtocolType.CUSTOM_TCP, ChannelDirection.READ_WRITE,
                "{\"host\":\"localhost\",\"port\":9002}"));
        channelService.create(channel("f1_modbus", "factory_1", "一号厂-PLC",
                ProtocolType.MODBUS_TCP, ChannelDirection.READ_ONLY,
                "{\"host\":\"localhost\",\"port\":5020,\"unitId\":1}"));
        channelService.create(channel("f2_mqtt", "factory_2", "二号厂-环境传感器",
                ProtocolType.MQTT, ChannelDirection.READ_ONLY,
                "{\"broker\":\"tcp://localhost:1883\",\"clientId\":\"sdncustom_f2\"}"));
        channelService.create(channel("f2_opcua", "factory_2", "二号厂-空压机",
                ProtocolType.OPCUA, ChannelDirection.READ_WRITE,
                "{\"endpoint\":\"opc.tcp://localhost:4840\"}"));
    }

    private void seedPoints() {
        pointService.create(point("f1_cycle_count", "factory_1", "装配计数",
                PointDataType.INT32, "pcs", false, null, binding("f1_tcp", "10001")));
        pointService.create(point("f1_line_run", "factory_1", "产线运行状态",
                PointDataType.BOOL, null, true, null, binding("f1_tcp", "10002")));
        pointService.create(point("f1_spindle_temp", "factory_1", "主轴温度",
                PointDataType.FLOAT32, "°C", false, 0.5, binding("f1_modbus", "40001")));
        pointService.create(point("f2_room_temp", "factory_2", "车间温度",
                PointDataType.FLOAT32, "°C", false, 0.2, binding("f2_mqtt", "env/temperature")));
        pointService.create(point("f2_room_humidity", "factory_2", "车间湿度",
                PointDataType.FLOAT32, "%", false, 1.0, binding("f2_mqtt", "env/humidity")));
        pointService.create(point("f2_compressor_press", "factory_2", "空压机压力",
                PointDataType.FLOAT32, "kPa", false, 0.5, binding("f2_opcua", "ns=2;s=Compressor/Pressure")));
    }

    private BusinessSystemDTO business(String id, String name, String description) {
        BusinessSystemDTO dto = new BusinessSystemDTO();
        dto.setBusinessId(id);
        dto.setBusinessName(name);
        dto.setDescription(description);
        return dto;
    }

    private ChannelDTO channel(String channelId, String businessId, String name,
                               ProtocolType protocolType, ChannelDirection direction, String config) {
        ChannelDTO dto = new ChannelDTO();
        dto.setChannelId(channelId);
        dto.setBusinessId(businessId);
        dto.setChannelName(name);
        dto.setProtocolType(protocolType);
        dto.setDirection(direction);
        dto.setConnectionConfig(config);
        dto.setAutoConnect(false);
        return dto;
    }

    private MeasurementPointDTO point(String pointId, String businessId, String name,
                                      PointDataType dataType, String unit, boolean writable,
                                      Double deadband, PointSourceDTO... bindings) {
        MeasurementPointDTO dto = new MeasurementPointDTO();
        dto.setPointId(pointId);
        dto.setBusinessId(businessId);
        dto.setPointName(name);
        dto.setDataType(dataType);
        dto.setUnit(unit);
        dto.setWritable(writable);
        dto.setDeadband(deadband);
        dto.setBindings(List.of(bindings));
        return dto;
    }

    private PointSourceDTO binding(String channelId, String address) {
        PointSourceDTO dto = new PointSourceDTO();
        dto.setChannelId(channelId);
        dto.setAddress(address);
        return dto;
    }
}
