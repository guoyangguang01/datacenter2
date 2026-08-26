import { useEffect, useState } from 'react';
import { Table, Select, Space, Tag, Button, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import { usePointStore } from '../stores/pointStore';
import { wsService } from '../services/websocket';
import type { PointValue, PointQuality } from '../types';

const qualityColors: Record<PointQuality, string> = {
  GOOD: 'green',
  BAD: 'red',
  UNCERTAIN: 'orange',
  COMM_LOST: 'default',
};

export default function DashboardPage() {
  const { channels, fetchChannels } = useChannelStore();
  const { points, pointValues, fetchPoints, fetchAllValues, updateValue } = usePointStore();
  const [selectedChannels, setSelectedChannels] = useState<string[]>([]);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  useEffect(() => {
    if (selectedChannels.length > 0) {
      fetchPoints(selectedChannels[0]);
    }
  }, [selectedChannels, fetchPoints]);

  useEffect(() => {
    if (points.length > 0) {
      fetchAllValues();
    }
  }, [points, fetchAllValues]);

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
    };
  }, [updateValue]);

  useEffect(() => {
    if (selectedChannels.length > 0) {
      wsService.subscribe(selectedChannels);
    }
    return () => {
      if (selectedChannels.length > 0) {
        wsService.unsubscribe(selectedChannels);
      }
    };
  }, [selectedChannels]);

  const handleRefresh = () => {
    if (selectedChannels.length > 0) {
      wsService.refresh(selectedChannels);
      fetchAllValues();
      message.success('Refreshed');
    }
  };

  const columns = [
    { title: 'Point ID', dataIndex: 'pointId', key: 'pointId' },
    { title: 'Name', dataIndex: 'pointName', key: 'pointName' },
    { title: 'Address', dataIndex: 'address', key: 'address' },
    { title: 'Type', dataIndex: 'dataType', key: 'dataType' },
    { title: 'Unit', dataIndex: 'unit', key: 'unit' },
    {
      title: 'Value',
      key: 'value',
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        return pv?.value ?? '-';
      },
    },
    {
      title: 'Quality',
      key: 'quality',
      render: (_: unknown, record: { pointId: string }) => {
        const pv = pointValues.get(record.pointId);
        const quality = pv?.quality ?? 'COMM_LOST';
        return <Tag color={qualityColors[quality]}>{quality}</Tag>;
      },
    },
    {
      title: 'Timestamp',
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
          <h2>Real-time Dashboard</h2>
          <Select
            mode="multiple"
            placeholder="Select Channels"
            style={{ width: 300 }}
            value={selectedChannels}
            onChange={setSelectedChannels}
            options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))}
          />
        </Space>
        <Button icon={<ReloadOutlined />} onClick={handleRefresh}>
          Refresh
        </Button>
      </div>
      <Table columns={columns} dataSource={points} rowKey="pointId" />
    </div>
  );
}
