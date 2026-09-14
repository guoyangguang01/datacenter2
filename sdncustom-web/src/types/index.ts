export type ProtocolType = 'CUSTOM_TCP' | 'MODBUS_TCP' | 'MQTT' | 'OPCUA';

export type ChannelDirection = 'READ_ONLY' | 'WRITE_ONLY' | 'READ_WRITE';

export type ChannelStatus = 'DISCONNECTED' | 'CONNECTED' | 'ERROR';

export type PointDataType = 'BOOL' | 'INT16' | 'INT32' | 'FLOAT32' | 'FLOAT64' | 'STRING';

export type PointQuality = 'GOOD' | 'BAD' | 'UNCERTAIN' | 'COMM_LOST';

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
  protocolType: ProtocolType;
  direction: ChannelDirection;
  connectionConfig: string;
  status: ChannelStatus;
  autoConnect: boolean;
  createTime: string;
  updateTime: string;
}

export interface PointSourceDTO {
  channelId: string;
  address: string;
}

export interface MeasurementPoint {
  pointId: string;
  businessId: string;
  pointName: string;
  dataType: PointDataType;
  unit: string;
  writable: boolean;
  deadband?: number;
  bindings: PointSourceDTO[];
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

// 数据导入导出 payload：只含业务与测点，通道（连接配置）不在其中
export interface DataExportPayload {
  businesses?: BusinessSystem[];
  points?: MeasurementPoint[];
}

export interface DataImportResult {
  businessCount: number;
  pointCount: number;
}

export interface ChannelImportResult {
  channelCount: number;
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
