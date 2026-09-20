export type ProtocolType = 'CUSTOM_TCP' | 'MODBUS_TCP' | 'MQTT' | 'OPCUA';

export type ChannelStatus = 'DISCONNECTED' | 'CONNECTED' | 'ERROR';

export type PointDataType = 'BOOL' | 'INT16' | 'INT32' | 'FLOAT32' | 'FLOAT64' | 'STRING';

export type PointQuality = 'GOOD' | 'BAD' | 'UNCERTAIN' | 'COMM_LOST';

export type PointDirection = 'INPUT' | 'OUTPUT';

export interface BusinessSystem {
  businessId: string;
  businessName: string;
  description?: string;
  createTime: string;
  updateTime: string;
}

export interface Channel {
  channelId: string;
  businessId: string;
  channelName: string;
  /** 外部系统代码（如 FZXT / SWGZ）；空值表示未编码，非空时业务内唯一 */
  code?: string;
  protocolType: ProtocolType;
  connectionConfig: string;
  status: ChannelStatus;
  autoConnect: boolean;
  createTime: string;
  updateTime: string;
}

export interface MeasurementPoint {
  pointId: string;
  businessId: string;
  pointName: string;
  channelId: string;
  address: string;
  dataType: PointDataType;
  unit: string;
  direction: PointDirection;
  referencePointId?: string;
  deadband?: number;
  createTime: string;
  updateTime: string;
}

export interface PointValue {
  pointId: string;
  value: unknown;
  quality: PointQuality;
  sourceChannelId: string;
  timestamp: number;
}

// 数据导入导出 payload：含业务、通道（可选，自动创建）、测点
export interface DataExportPayload {
  businesses?: BusinessSystem[];
  channels?: Channel[];
  points?: MeasurementPoint[];
}

export interface DataImportResult {
  businessCount: number;
  pointCount: number;
}

export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface SystemStatus {
  uptimeSeconds: number;
  channelsConnected: number;
  channelsTotal: number;
  wsSessions: number;
  cycleP99Ms: number;
  acquisitionFailures: number;
  changedValuesTotal: number;
  historyCircuitOpen: boolean;
  tdengineEnabled: boolean;
}
