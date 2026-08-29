package com.sdncustom.protocol;

import com.sdncustom.common.model.enums.ProtocolType;

/**
 * 协议适配器工厂：按类型创建独立的适配器实例。
 * 实例的生命周期（缓存、断开、销毁）由 {@link ProtocolRegistry} 统一管理。
 */
public interface ProtocolAdapterFactory {

    ProtocolType protocolType();

    ProtocolAdapter create();
}
