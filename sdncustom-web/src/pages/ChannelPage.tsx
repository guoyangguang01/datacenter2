import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, LinkOutlined, DisconnectOutlined, EyeOutlined } from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import type { Channel, ProtocolType, ChannelDirection } from '../types';

const protocolOptions = [
  { label: '自定义TCP', value: 'CUSTOM_TCP' },
  { label: 'Modbus TCP', value: 'MODBUS_TCP' },
  { label: 'MQTT', value: 'MQTT' },
  { label: 'OPC-UA', value: 'OPCUA' },
];

const directionOptions = [
  { label: '只读', value: 'READ_ONLY' },
  { label: '只写', value: 'WRITE_ONLY' },
  { label: '读/写', value: 'READ_WRITE' },
];

const statusColors = {
  CONNECTED: 'green',
  DISCONNECTED: 'default',
  ERROR: 'red',
};

export default function ChannelPage() {
  const { channels, loading, fetchChannels, createChannel, updateChannel, deleteChannel, connectChannel, disconnectChannel } = useChannelStore();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Channel | null>(null);
  const [form] = Form.useForm();

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  const handleAdd = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const handleEdit = (record: Channel) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    await deleteChannel(id);
    message.success('已删除');
  };

  const handleConnect = async (id: string) => {
    await connectChannel(id);
    message.success('已连接');
  };

  const handleDisconnect = async (id: string) => {
    await disconnectChannel(id);
    message.success('已断开');
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (editing) {
      await updateChannel(editing.channelId, values);
      message.success('已更新');
    } else {
      await createChannel(values);
      message.success('已创建');
    }
    setModalOpen(false);
  };

  const columns = [
    { title: 'ID', dataIndex: 'channelId', key: 'channelId' },
    { title: '名称', dataIndex: 'channelName', key: 'channelName' },
    { title: '协议', dataIndex: 'protocolType', key: 'protocolType' },
    { title: '方向', dataIndex: 'direction', key: 'direction' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={statusColors[status as keyof typeof statusColors]}>{status}</Tag>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: Channel) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          <Button size="small" icon={<DeleteOutlined />} danger onClick={() => handleDelete(record.channelId)} />
          {record.status === 'DISCONNECTED' ? (
            <Button size="small" type="primary" icon={<LinkOutlined />} onClick={() => handleConnect(record.channelId)}>
              连接
            </Button>
          ) : (
            <>
              <Button size="small" icon={<DisconnectOutlined />} onClick={() => handleDisconnect(record.channelId)}>
                断开
              </Button>
              <Button
                size="small"
                type="primary"
                ghost
                icon={<EyeOutlined />}
                onClick={() => window.open(`/monitor?channelId=${record.channelId}`, '_blank')}
              >
                监控
              </Button>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h2>通道管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          添加通道
        </Button>
      </div>
      <Table columns={columns} dataSource={channels} rowKey="channelId" loading={loading} />

      <Modal
        title={editing ? '编辑通道' : '添加通道'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="channelId" label="通道ID" rules={[{ required: true }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="channelName" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="protocolType" label="协议" rules={[{ required: true }]}>
            <Select options={protocolOptions} />
          </Form.Item>
          <Form.Item name="direction" label="方向" rules={[{ required: true }]}>
            <Select options={directionOptions} />
          </Form.Item>
          <Form.Item name="connectionConfig" label="连接配置 (JSON)">
            <Input.TextArea rows={4} placeholder='{"host":"192.168.1.1","port":502}' />
          </Form.Item>
          <Form.Item name="autoConnect" label="自动连接" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
