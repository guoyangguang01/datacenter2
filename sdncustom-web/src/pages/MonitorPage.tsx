import { useEffect, useState, useRef, useCallback } from 'react';
import { Table, Select, Space, Tag, Button, Switch, Badge, Card, Empty, Tooltip, message } from 'antd';
import {
  PauseCircleOutlined,
  PlayCircleOutlined,
  ClearOutlined,
  DownloadOutlined,
  WifiOutlined,
  DisconnectOutlined,
} from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import { usePointStore } from '../stores/pointStore';
import { wsService } from '../services/websocket';
import type { PointValue, PointQuality, ChannelStatus } from '../types';

const qualityColors: Record<PointQuality, string> = {
  GOOD: 'green',
  BAD: 'red',
  UNCERTAIN: 'orange',
  COMM_LOST: 'default',
};

interface LogEntry {
  id: number;
  time: string;
  pointId: string;
  value: unknown;
  quality: PointQuality;
  channelId: string;
}

export default function MonitorPage() {
  const { channels, fetchChannels, updateStatus } = useChannelStore();
  const { points, pointValues, fetchPointsForChannels, fetchAllValues, updateValue, error } = usePointStore();

  // 从 URL 读取 channelId 参数（仅在通道列表存在该通道时才自动选中）
  const [urlChannelId] = useState(() => new URLSearchParams(window.location.search).get('channelId'));
  const appliedUrlRef = useRef(false);
  const [selectedChannels, setSelectedChannels] = useState<string[]>([]);

  const [paused, setPaused] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [autoScroll, setAutoScroll] = useState(true);
  const logRef = useRef<HTMLDivElement>(null);
  const logIdRef = useRef(0);
  const pausedRef = useRef(false);
  const selectedChannelsRef = useRef<string[]>(selectedChannels);

  useEffect(() => {
    pausedRef.current = paused;
  }, [paused]);

  useEffect(() => {
    selectedChannelsRef.current = selectedChannels;
  }, [selectedChannels]);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  // URL 注入的 channelId：仅在通道列表中存在时才自动选中，否则忽略
  useEffect(() => {
    if (appliedUrlRef.current || !urlChannelId) return;
    if (channels.some((c) => c.channelId === urlChannelId)) {
      appliedUrlRef.current = true;
      setSelectedChannels([urlChannelId]);
    } else if (channels.length > 0) {
      appliedUrlRef.current = true; // 列表已加载但 URL 中的通道不存在 → 忽略
    }
  }, [channels, urlChannelId]);

  // 选中通道变化时获取测点（合并，保留其它通道已加载的测点）
  useEffect(() => {
    if (selectedChannels.length > 0) {
      fetchPointsForChannels(selectedChannels);
    }
  }, [selectedChannels, fetchPointsForChannels]);

  useEffect(() => {
    if (points.length > 0) {
      fetchAllValues();
    }
  }, [points, fetchAllValues]);

  // 展示 store 中的错误信息
  useEffect(() => {
    if (error) message.error(error);
  }, [error]);

  // WebSocket 连接与数据监听：仅在挂载时连接一次，卸载时断开
  useEffect(() => {
    const handleData = (data: unknown) => {
      const msg = data as { values?: PointValue[] };
      if (!msg.values) return;

      msg.values.forEach((v) => updateValue(v));

      if (pausedRef.current) return;

      const sel = selectedChannelsRef.current;
      const newEntries: LogEntry[] = msg.values
        .filter((v) => sel.length === 0 || sel.includes(v.sourceChannelId))
        .map((v) => ({
          id: ++logIdRef.current,
          time: (() => {
            const d = new Date(v.timestamp);
            return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}.${String(d.getMilliseconds()).padStart(3, '0')}`;
          })(),
          pointId: v.pointId,
          value: v.value,
          quality: v.quality,
          channelId: v.sourceChannelId,
        }));

      if (newEntries.length > 0) {
        setLogs((prev) => {
          const combined = [...prev, ...newEntries];
          return combined.length > 500 ? combined.slice(-500) : combined;
        });
      }
    };

    const handleConnected = () => setWsConnected(true);
    const handleDisconnected = () => setWsConnected(false);
    const handleChannelStatus = (data: unknown) => {
      const msg = data as { channelId?: string; status?: ChannelStatus };
      if (msg.channelId && msg.status) {
        updateStatus(msg.channelId, msg.status);
      }
    };

    wsService.connect();
    wsService.on('data', handleData);
    wsService.on('connected', handleConnected);
    wsService.on('disconnected', handleDisconnected);
    wsService.on('channel_status', handleChannelStatus);

    return () => {
      wsService.off('data', handleData);
      wsService.off('connected', handleConnected);
      wsService.off('disconnected', handleDisconnected);
      wsService.off('channel_status', handleChannelStatus);
      wsService.disconnect();
    };
  }, [updateValue, updateStatus]);

  // 订阅/取消订阅通道；断线重连后自动重新订阅
  useEffect(() => {
    const doSubscribe = () => {
      if (selectedChannels.length > 0) {
        wsService.subscribe(selectedChannels);
      }
    };
    doSubscribe();
    wsService.on('connected', doSubscribe);
    return () => {
      wsService.off('connected', doSubscribe);
      if (selectedChannels.length > 0) {
        wsService.unsubscribe(selectedChannels);
      }
    };
  }, [selectedChannels]);

  // 自动滚动
  useEffect(() => {
    if (autoScroll && logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const handleClearLogs = useCallback(() => {
    setLogs([]);
  }, []);

  const handleExportLogs = useCallback(() => {
    const header = '时间,测点ID,值,质量,通道\n';
    const csv = logs
      .map((l) => `${l.time},${l.pointId},${l.value},${l.quality},${l.channelId}`)
      .join('\n');
    const blob = new Blob(['﻿' + header + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `monitor_${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }, [logs]);

  const handleRefresh = useCallback(() => {
    if (selectedChannels.length > 0) {
      wsService.refresh(selectedChannels);
      fetchAllValues();
    }
  }, [selectedChannels, fetchAllValues]);

  const urlChannelExists = urlChannelId ? channels.some((c) => c.channelId === urlChannelId) : false;

  const columns = [
    { title: '测点ID', dataIndex: 'pointId', key: 'pointId', width: 150 },
    {
      title: '名称',
      key: 'pointName',
      width: 150,
      render: (_: unknown, record: { pointId: string }) => {
        const pt = points.find((p) => p.pointId === record.pointId);
        return pt?.pointName ?? record.pointId;
      },
    },
    { title: '地址', dataIndex: 'address', key: 'address', width: 120 },
    { title: '类型', dataIndex: 'dataType', key: 'dataType', width: 90 },
    { title: '单位', dataIndex: 'unit', key: 'unit', width: 80 },
    {
      title: '当前值',
      key: 'value',
      width: 120,
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        return <span style={{ fontWeight: 'bold', fontFamily: 'monospace' }}>{pv?.value != null ? String(pv.value) : '-'}</span>;
      },
    },
    {
      title: '质量',
      key: 'quality',
      width: 100,
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        const quality = pv?.quality ?? 'COMM_LOST';
        return <Tag color={qualityColors[quality]}>{quality}</Tag>;
      },
    },
    {
      title: '来源通道',
      key: 'sourceChannel',
      width: 130,
      render: (_: unknown, record: { pointId: string }) => {
        const ch = pointValues.get(record.pointId)?.sourceChannelId;
        if (!ch) return '-';
        const name = channels.find((c) => c.channelId === ch)?.channelName ?? ch;
        return <Tag color="geekblue">{name}</Tag>;
      },
    },
    {
      title: '更新时间',
      key: 'timestamp',
      width: 180,
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        return pv?.timestamp ? new Date(pv.timestamp).toLocaleString('zh-CN') : '-';
      },
    },
  ];

  return (
    <div>
      {/* 顶部工具栏 */}
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space size="middle">
          <h2 style={{ margin: 0 }}>
            数据监控
            {urlChannelExists && (
              <Tag color="blue" style={{ marginLeft: 8 }}>
                {channels.find((c) => c.channelId === urlChannelId)?.channelName ?? urlChannelId}
              </Tag>
            )}
          </h2>
          <Badge
            status={wsConnected ? 'success' : 'error'}
            text={
              <span style={{ fontSize: 13 }}>
                {wsConnected ? (
                  <><WifiOutlined /> 已连接</>
                ) : (
                  <><DisconnectOutlined /> 未连接</>
                )}
              </span>
            }
          />
        </Space>
        <Space>
          <Select
            mode="multiple"
            placeholder="选择监控通道"
            style={{ minWidth: 300 }}
            value={selectedChannels}
            onChange={setSelectedChannels}
            options={channels
              .filter((c) => c.status === 'CONNECTED')
              .map((c) => ({
                label: (
                  <Space>
                    <Badge status="success" />
                    {c.channelName}
                    <Tag>{c.protocolType}</Tag>
                  </Space>
                ),
                value: c.channelId,
              }))}
          />
          <Tooltip title="刷新数据">
            <Button icon={<DownloadOutlined />} onClick={handleRefresh} />
          </Tooltip>
        </Space>
      </div>

      {/* 实时数据表格 */}
      <Card
        title="实时数据"
        size="small"
        style={{ marginBottom: 16 }}
        bodyStyle={{ padding: 0 }}
      >
        <Table
          columns={columns}
          dataSource={points.filter((p) => {
            if (selectedChannels.length === 0) return true;
            // 绑定集：任一绑定通道命中选中通道即展示
            return (p.bindings ?? []).some((s) => selectedChannels.includes(s.channelId));
          })}
          rowKey="pointId"
          size="small"
          pagination={false}
          scroll={{ y: 300 }}
          locale={{ emptyText: <Empty description={'请选择要监控的通道'} /> }}
        />
      </Card>

      {/* 数据日志 */}
      <Card
        title={
          <Space>
            <span>数据日志</span>
            <Tag color="blue">{logs.length} 条</Tag>
          </Space>
        }
        size="small"
        extra={
          <Space>
            <span style={{ fontSize: 12, color: '#999' }}>暂停</span>
            <Switch
              checkedChildren={<PauseCircleOutlined />}
              unCheckedChildren={<PlayCircleOutlined />}
              checked={paused}
              onChange={setPaused}
            />
            <span style={{ fontSize: 12, color: '#999' }}>自动滚动</span>
            <Switch
              size="small"
              checked={autoScroll}
              onChange={setAutoScroll}
            />
            <Tooltip title="清空日志">
              <Button size="small" icon={<ClearOutlined />} onClick={handleClearLogs} />
            </Tooltip>
            <Tooltip title="导出 CSV">
              <Button size="small" icon={<DownloadOutlined />} onClick={handleExportLogs} />
            </Tooltip>
          </Space>
        }
      >
        <div
          ref={logRef}
          style={{
            height: 300,
            overflowY: 'auto',
            fontFamily: 'Consolas, "Courier New", monospace',
            fontSize: 12,
            background: '#1e1e1e',
            color: '#d4d4d4',
            padding: 8,
            borderRadius: 4,
          }}
        >
          {logs.length === 0 ? (
            <div style={{ color: '#666', textAlign: 'center', paddingTop: 40 }}>
              {selectedChannels.length === 0 ? '请先选择监控通道' : '等待数据...'}
            </div>
          ) : (
            logs.map((log) => (
              <div key={log.id} style={{ lineHeight: '20px', borderBottom: '1px solid #333' }}>
                <span style={{ color: '#6a9955' }}>{log.time}</span>
                {' '}
                <span style={{ color: '#569cd6' }}>{log.pointId}</span>
                {' = '}
                <span style={{ color: '#ce9178', fontWeight: 'bold' }}>{String(log.value)}</span>
                {' '}
                <Tag
                  color={qualityColors[log.quality]}
                  style={{ fontSize: 11, lineHeight: '16px', padding: '0 4px' }}
                >
                  {log.quality}
                </Tag>
                <span style={{ color: '#4ec9b0', fontSize: 11 }}>  [{log.channelId}]</span>
              </div>
            ))
          )}
        </div>
      </Card>
    </div>
  );
}
