package com.sdncustom.common.model.enums;

/**
 * 测点数据流向：
 * OUTPUT 输出测点——数据从外部经绑定通道采集进来（原语义）；
 * INPUT  输入测点——值由所引用的输出测点驱动，经绑定通道写出到外部。
 */
public enum PointDirection {
    INPUT,
    OUTPUT
}
