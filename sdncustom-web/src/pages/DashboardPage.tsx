import { useEffect, useState } from 'react';
import { Table, Select, Space, Tag, Button, Card, Col, Row, Statistic, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import { usePointStore } from '../stores/pointStore';
import { wsService } from '../services/websocket';
import { systemApi } from '../services/api';
import type { PointValue, PointQuality, SystemStatus } from '../types';

const qualityColors: Record<PointQuality, string> = {
  GOOD: 'green',
  BAD: 'red',
  UNCERTAIN: 'orange',
  COMM_LOST: 'default',
};

export default function DashboardPage() {
  const { channels, fetchChannels } = useChannelStore();
  const { points, pointValues, fetchPointsForChannels, fetchAllValues, updateValue, error } = usePointStore();
  const [selectedChannels, setSelectedChannels] = useState<string[]>([]);
  const [status, setStatus] = useState<SystemStatus | null>(null);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  useEffect(() => {
    let alive = true;
    const load = () => {
      systemApi.getStatus()
        .then((res) => {
          if (alive && res.data.code === 200) setStatus(res.data.data);
        })
        .catch(() => { /* 状态卡刷新失败静默，下一次轮询重试 */ });
    };
    load();
    const timer = setInterval(load, 10000);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  // 获取所有选中通道的测点并合并（复用 store 的合并逻辑）
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

  useEffect(() => {
    if (error) message.error(error);
  }, [error]);

  // WebSocket：仅在挂载时连接一次，卸载时断开
  useEffect(() => {
    const handleData = (data: unknown) => {
      const msg = data as { values?: PointValue[] };
      if (msg.values) {
        msg.values.forEach((v) => updateValue(v));
      }
    };

    wsService.connect();
    wsService.on('data', handleData);

    return () => {
      wsService.off('data', handleData);
      wsService.disconnect();
    };
  }, [updateValue]);

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

  const handleRefresh = () => {
    if (selectedChannels.length > 0) {
      wsService.refresh(selectedChannels);
      fetchAllValues();
      message.success('已刷新');
    }
  };

  const columns = [
    { title: '测点ID', dataIndex: 'pointId', key: 'pointId' },
    { title: '名称', dataIndex: 'pointName', key: 'pointName' },
    { title: '地址', dataIndex: 'address', key: 'address' },
    { title: '类型', dataIndex: 'dataType', key: 'dataType' },
    { title: '单位', dataIndex: 'unit', key: 'unit' },
    {
      title: '值',
      key: 'value',
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        return pv?.value ?? '-';
      },
    },
    {
      title: '质量',
      key: 'quality',
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        const quality = pv?.quality ?? 'COMM_LOST';
        return <Tag color={qualityColors[quality]}>{quality}</Tag>;
      },
    },
    {
      title: '时间戳',
      key: 'timestamp',
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        return pv?.timestamp ? new Date(pv.timestamp).toLocaleString() : '-';
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <h2>实时仪表盘</h2>
          <Select
            mode="multiple"
            placeholder="选择通道"
            style={{ width: 300 }}
            value={selectedChannels}
            onChange={setSelectedChannels}
            options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))}
          />
        </Space>
        <Button icon={<ReloadOutlined />} onClick={handleRefresh}>
          刷新
        </Button>
      </div>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small">
            <Statistic title="通道（已连接/总数）" value={status ? `${status.channelsConnected}/${status.channelsTotal}` : '-'} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="在线实时会话" value={status ? status.wsSessions : '-'} />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <Statistic
              title="采集周期 P99 / 累计失败"
              value={status ? `${status.cycleP99Ms.toFixed(1)}ms / ${status.acquisitionFailures}` : '-'}
              valueStyle={status && status.acquisitionFailures > 0 ? { color: '#cf1322' } : undefined}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <div style={{ color: 'rgba(0,0,0,0.45)', fontSize: 14, marginBottom: 4 }}>历史存储</div>
            {
              !status ? '-' :
              !status.tdengineEnabled ? <Tag>未启用</Tag> :
              status.historyCircuitOpen ? <Tag color="red">熔断中</Tag> : <Tag color="green">正常</Tag>
            }
          </Card>
        </Col>
      </Row>
      <Table
        columns={columns}
        dataSource={points.filter(
          (p) => selectedChannels.length === 0 || selectedChannels.includes(p.channelId)
        )}
        rowKey="pointId"
      />
    </div>
  );
}
