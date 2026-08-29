package com.sdncustom.protocol.tcp;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;
import com.sdncustom.common.model.enums.PointDataType;
import com.sdncustom.common.model.enums.PointQuality;
import com.sdncustom.common.model.enums.ProtocolType;
import com.sdncustom.protocol.ProtocolAdapter;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CustomTcpAdapter 单元测试
 */
@DisplayName("CustomTcpAdapter 测试")
class CustomTcpAdapterTest {

    private CustomTcpAdapter adapter;
    private Channel testChannel;
    private MeasurementPoint testPoint;

    @BeforeEach
    void setUp() {
        adapter = new CustomTcpAdapter();

        testChannel = new Channel();
        testChannel.setChannelId("ch_tcp_001");
        testChannel.setChannelName("TCP测试通道");
        testChannel.setProtocolType(ProtocolType.CUSTOM_TCP);
        testChannel.setConnectionConfig("{\"host\":\"localhost\",\"port\":9001}");

        testPoint = new MeasurementPoint();
        testPoint.setPointId("point_001");
        testPoint.setPointName("测试测点");
        testPoint.setChannelId("ch_tcp_001");
        testPoint.setAddress("int16_001");
        testPoint.setDataType(PointDataType.INT16);
        testPoint.setUnit("°C");
    }

    @AfterEach
    void tearDown() {
        try {
            adapter.disconnect();
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("初始状态 - 未连接")
    void initialState() {
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("连接失败 - 服务器不可达")
    void connectFailed() {
        testChannel.setConnectionConfig("{\"host\":\"localhost\",\"port\":19999}");

        assertThrows(RuntimeException.class, () -> adapter.connect(testChannel));
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("断开连接")
    void disconnect() {
        adapter.disconnect();

        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("读取测点 - 未连接返回 COMM_LOST")
    void readPointNotConnected() {
        PointValue pv = adapter.readPoint(testPoint);
        assertNotNull(pv);
        assertEquals(PointQuality.COMM_LOST, pv.getQuality());
        assertEquals("point_001", pv.getPointId());
    }

    @Test
    @DisplayName("读取多个测点 - 未连接")
    void readPointsNotConnected() {
        assertThrows(RuntimeException.class, () -> adapter.readPoints(Arrays.asList(testPoint)));
    }

    @Test
    @DisplayName("写入测点 - 未连接")
    void writePointNotConnected() {
        assertThrows(RuntimeException.class, () -> adapter.writePoint(testPoint, 100));
    }

    @Test
    @DisplayName("工厂每次创建独立新实例")
    void factoryCreatesNewInstances() {
        CustomTcpAdapterFactory factory = new CustomTcpAdapterFactory();
        ProtocolAdapter a = factory.create();
        ProtocolAdapter b = factory.create();

        assertNotNull(a);
        assertNotSame(a, b);
        assertEquals(ProtocolType.CUSTOM_TCP, factory.protocolType());
    }

    @Test
    @DisplayName("TCP消息编码解码")
    void tcpMessageEncodeDecode() {
        TcpMessage original = new TcpMessage(TcpCommand.READ_REQUEST, "{\"pointIds\":[\"p1\",\"p2\"]}");

        byte[] encoded = original.encode();
        TcpMessage decoded = TcpMessage.decode(encoded);

        assertEquals(original.getCommand(), decoded.getCommand());
        assertEquals(original.getBody(), decoded.getBody());
    }

    @Test
    @DisplayName("TCP消息 - 空消息体")
    void tcpMessageEmptyBody() {
        TcpMessage original = new TcpMessage(TcpCommand.HEARTBEAT_REQUEST, "");

        byte[] encoded = original.encode();
        TcpMessage decoded = TcpMessage.decode(encoded);

        assertEquals(original.getCommand(), decoded.getCommand());
        assertEquals("", decoded.getBody());
    }

    @Test
    @DisplayName("TCP消息 - 大消息体")
    void tcpMessageLargeBody() {
        String largeBody = "A".repeat(10000);
        TcpMessage original = new TcpMessage(TcpCommand.READ_RESPONSE, largeBody);

        byte[] encoded = original.encode();
        TcpMessage decoded = TcpMessage.decode(encoded);

        assertEquals(original.getCommand(), decoded.getCommand());
        assertEquals(largeBody, decoded.getBody());
    }

    @Test
    @DisplayName("TCP命令码常量")
    void tcpCommandConstants() {
        assertEquals(0x01, TcpCommand.READ_REQUEST);
        assertEquals(0x02, TcpCommand.READ_RESPONSE);
        assertEquals(0x03, TcpCommand.WRITE_REQUEST);
        assertEquals(0x04, TcpCommand.WRITE_RESPONSE);
        assertEquals(0x05, TcpCommand.VALUE_PUSH);
        assertEquals(0x10, TcpCommand.HEARTBEAT_REQUEST);
        assertEquals(0x11, TcpCommand.HEARTBEAT_RESPONSE);
    }
}
