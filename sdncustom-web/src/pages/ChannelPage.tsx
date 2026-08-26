import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, LinkOutlined, DisconnectOutlined } from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import type { Channel, ProtocolType, ChannelDirection } from '../types';

const protocolOptions = [
  { label: 'Custom TCP', value: 'CUSTOM_TCP' },
  { label: 'Modbus TCP', value: 'MODBUS_TCP' },
  { label: 'MQTT', value: 'MQTT' },
  { label: 'OPC-UA', value: 'OPCUA' },
];

const directionOptions = [
  { label: 'Read Only', value: 'READ_ONLY' },
  { label: 'Write Only', value: 'WRITE_ONLY' },
  { label: 'Read/Write', value: 'READ_WRITE' },
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
    message.success('Deleted');
  };

  const handleConnect = async (id: string) => {
    await connectChannel(id);
    message.success('Connected');
  };

  const handleDisconnect = async (id: string) => {
    await disconnectChannel(id);
    message.success('Disconnected');
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (editing) {
      await updateChannel(editing.channelId, values);
      message.success('Updated');
    } else {
      await createChannel(values);
      message.success('Created');
    }
    setModalOpen(false);
  };

  const columns = [
    { title: 'ID', dataIndex: 'channelId', key: 'channelId' },
    { title: 'Name', dataIndex: 'channelName', key: 'channelName' },
    { title: 'Protocol', dataIndex: 'protocolType', key: 'protocolType' },
    { title: 'Direction', dataIndex: 'direction', key: 'direction' },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => (
        <Tag color={statusColors[status as keyof typeof statusColors]}>{status}</Tag>
      ),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_: unknown, record: Channel) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          <Button size="small" icon={<DeleteOutlined />} danger onClick={() => handleDelete(record.channelId)} />
          {record.status === 'DISCONNECTED' ? (
            <Button size="small" type="primary" icon={<LinkOutlined />} onClick={() => handleConnect(record.channelId)}>
              Connect
            </Button>
          ) : (
            <Button size="small" icon={<DisconnectOutlined />} onClick={() => handleDisconnect(record.channelId)}>
              Disconnect
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <h2>Channel Management</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          Add Channel
        </Button>
      </div>
      <Table columns={columns} dataSource={channels} rowKey="channelId" loading={loading} />

      <Modal
        title={editing ? 'Edit Channel' : 'Add Channel'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="channelId" label="Channel ID" rules={[{ required: true }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="channelName" label="Name" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="protocolType" label="Protocol" rules={[{ required: true }]}>
            <Select options={protocolOptions} />
          </Form.Item>
          <Form.Item name="direction" label="Direction" rules={[{ required: true }]}>
            <Select options={directionOptions} />
          </Form.Item>
          <Form.Item name="connectionConfig" label="Connection Config (JSON)">
            <Input.TextArea rows={4} placeholder='{"host":"192.168.1.1","port":502}' />
          </Form.Item>
          <Form.Item name="autoConnect" label="Auto Connect" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
