package com.sdncustom.protocol;

import com.sdncustom.common.model.Channel;
import com.sdncustom.common.model.MeasurementPoint;
import com.sdncustom.common.model.PointValue;

import java.util.List;

/**
 * 协议适配器接口
 * 所有协议实现必须实现此接口
 */
public interface ProtocolAdapter {

    /**
     * 连接到外部系统
     *
     * @param channel Channel 配置
     */
    void connect(Channel channel);

    /**
     * 断开连接
     */
    void disconnect();

    /**
     * 读取单个测点值
     *
     * @param point 测点配置
     * @return 测点值
     */
    PointValue readPoint(MeasurementPoint point);

    /**
     * 写入单个测点值
     *
     * @param point 测点配置
     * @param value 要写入的值
     */
    void writePoint(MeasurementPoint point, Object value);

    /**
     * 批量读取测点值
     *
     * @param points 测点列表
     * @return 测点值列表
     */
    List<PointValue> readPoints(List<MeasurementPoint> points);

    /**
     * 是否已连接
     *
     * @return true if connected
     */
    boolean isConnected();

    /**
     * 连接成功后的钩子，由通道生命周期管理层统一调用。
     * 订阅型协议（如 MQTT）在此为测点建立订阅；请求/响应型协议无需覆写。
     * 对同一测点重复调用应当幂等。
     *
     * @param points 该通道下的测点
     */
    default void onConnected(List<MeasurementPoint> points) {
        // no-op by default
    }
}
