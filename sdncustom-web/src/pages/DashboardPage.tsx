import { useEffect, useState } from 'react';
import { Table, Select, Space, Tag, Button, Card, Col, Row, Statistic, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import { usePointStore } from '../stores/pointStore';
import { useBusinessStore } from '../stores/businessStore';
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
  const { points, pointValues, fetchPoints, fetchAllValues, updateValue, error } = usePointStore();
  const currentBusinessId = useBusinessStore((s) => s.currentBusinessId);
  const [selectedChannels, setSelectedChannels] = useState<string[]>([]);
  const [status, setStatus] = useState<SystemStatus | null>(null);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels, currentBusinessId]);

  // 切换业务后旧筛选通道不再属于当前业务
  useEffect(() => {
    setSelectedChannels([]);
  }, [currentBusinessId]);

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

  // 仪表盘显示当前业务全部数据：挂载/切换业务即加载该业务全部测点
  useEffect(() => {
    fetchPoints();
  }, [fetchPoints, currentBusinessId]);

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

  // 订阅全部通道以接收所有实时数据；断线重连后自动重新订阅
  useEffect(() => {
    const allChannelIds = channels.map((c) => c.channelId);
    if (allChannelIds.length === 0) return;
    const doSubscribe = () => wsService.subscribe(allChannelIds);
    doSubscribe();
    wsService.on('connected', doSubscribe);
    return () => {
      wsService.off('connected', doSubscribe);
      wsService.unsubscribe(allChannelIds);
    };
  }, [channels]);

  const handleRefresh = () => {
    if (channels.length > 0) {
      wsService.refresh(channels.map((c) => c.channelId));
      fetchAllValues();
      message.success('已刷新');
    }
  };

  const inputCount = points.filter((p) => p.direction === 'INPUT').length;
  const outputCount = points.filter((p) => p.direction === 'OUTPUT').length;

  const columns = [
    { title: '测点ID', dataIndex: 'pointId', key: 'pointId' },
    { title: '名称', dataIndex: 'pointName', key: 'pointName' },
    {
      title: '来源',
      key: 'source',
      render: (_: unknown, record: { pointId: string }) => {
        const ch = pointValues.get(record.pointId)?.sourceChannelId;
        if (!ch) return '-';
        const name = channels.find((c) => c.channelId === ch)?.channelName ?? ch;
        return <Tag color="geekblue">{name}</Tag>;
      },
    },
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
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card size="small">
            <Statistic title="输出测点（从外部采集）" value={outputCount} valueStyle={{ color: '#52c41a' }} />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small">
            <Statistic title="输入测点（写出到外部）" value={inputCount} valueStyle={{ color: '#1677ff' }} />
          </Card>
        </Col>
      </Row>
      <Table
        columns={columns}
        dataSource={points.filter(
          (p) => selectedChannels.length === 0 || selectedChannels.includes(p.channelId)
        )}
        rowKey="pointId"
        pagination={{
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100, 200],
          showTotal: (total) => `共 ${total} 条`,
        }}
      />
    </div>
  );
}
