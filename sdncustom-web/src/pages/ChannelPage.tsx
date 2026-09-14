import { useEffect, useState, useRef } from 'react';
import { Table, Button, Modal, Form, Input, Select, Switch, Space, Tag, message, InputNumber, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, LinkOutlined, DisconnectOutlined, EyeOutlined, UploadOutlined } from '@ant-design/icons';
import { useChannelStore } from '../stores/channelStore';
import { channelApi } from '../services/api';
import { useBusinessStore } from '../stores/businessStore';
import type { Channel } from '../types';

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

// 解析连接配置 JSON
function parseConnectionConfig(config: string | undefined): Record<string, unknown> {
  if (!config) return {};
  try {
    return JSON.parse(config);
  } catch {
    return {};
  }
}

export default function ChannelPage() {
  const { channels, loading, error, clearError, fetchChannels, createChannel, updateChannel, deleteChannel, connectChannel, disconnectChannel } = useChannelStore();
  const currentBusinessId = useBusinessStore((s) => s.currentBusinessId);
  const businessesLoaded = useBusinessStore((s) => s.businesses.length > 0);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Channel | null>(null);
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [protocolType, setProtocolType] = useState<string>('CUSTOM_TCP');

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels, currentBusinessId]);

  // 展示 store 中的错误信息；弹出后立即清空，否则重新进入本页会重复弹同一条
  useEffect(() => {
    if (error) {
      message.error(error);
      clearError();
    }
  }, [error, clearError]);

  const handleAdd = () => {
    setEditing(null);
    form.resetFields();
    setProtocolType('CUSTOM_TCP');
    setModalOpen(true);
  };

  const handleEdit = (record: Channel) => {
    setEditing(record);
    const config = parseConnectionConfig(record.connectionConfig);
    form.setFieldsValue({
      channelId: record.channelId,
      channelName: record.channelName,
      protocolType: record.protocolType,
      direction: record.direction,
      autoConnect: record.autoConnect,
      // TCP / Modbus
      host: config.host || 'localhost',
      port: config.port || (record.protocolType === 'MODBUS_TCP' ? 502 : 9002),
      unitId: config.unitId || 1,
      // MQTT
      broker: config.broker || 'tcp://localhost:1883',
      clientId: config.clientId || '',
      username: config.username || '',
      password: config.password || '',
      // OPC-UA
      endpoint: config.endpoint || 'opc.tcp://localhost:4840',
    });
    setProtocolType(record.protocolType);
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteChannel(id);
      message.success('已删除');
    } catch {
      // 错误信息已通过 store.error 展示
    }
  };

  // 导入通道配置（仅通道；业务与测点走「测点管理」页的数据导入）
  const handleImport = async (file: File) => {
    try {
      const text = await file.text();
      const data = JSON.parse(text);
      const channels = Array.isArray(data) ? data : data.channels;

      if (!Array.isArray(channels)) {
        message.error('不支持的文件格式：需要通道数组或 {channels: [...]}');
        return;
      }

      const res = await channelApi.importConfig({ channels });
      if (res.data.code !== 200) {
        message.error(res.data.message || '导入失败');
        return;
      }
      await fetchChannels();
      message.success(`导入成功，共 ${res.data.data.channelCount} 个通道`);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '导入失败，请检查文件格式');
    }
  };

  const handleConnect = async (id: string) => {
    try {
      await connectChannel(id);
      message.success('已连接');
    } catch {
      // 错误信息已通过 store.error 展示
    }
  };

  const handleDisconnect = async (id: string) => {
    try {
      await disconnectChannel(id);
      message.success('已断开');
    } catch {
      // 错误信息已通过 store.error 展示
    }
  };

  const handleSubmit = async () => {
    // 校验失败时 antd 已在表单上标红；这里吞掉 rejection，避免未处理的 promise
    const values = await form.validateFields().catch(() => null);
    if (!values) return;

    // 根据协议类型构建连接配置 JSON
    let connectionConfig = '';
    switch (values.protocolType) {
      case 'CUSTOM_TCP':
        connectionConfig = JSON.stringify({
          host: values.host || 'localhost',
          port: values.port || 9002,
        });
        break;
      case 'MODBUS_TCP':
        connectionConfig = JSON.stringify({
          host: values.host || 'localhost',
          port: values.port || 502,
          unitId: values.unitId || 1,
        });
        break;
      case 'MQTT':
        connectionConfig = JSON.stringify({
          broker: values.broker || 'tcp://localhost:1883',
          clientId: values.clientId || `sdncustom_${Date.now()}`,
          ...(values.username ? { username: values.username } : {}),
          ...(values.password ? { password: values.password } : {}),
        });
        break;
      case 'OPCUA':
        connectionConfig = JSON.stringify({
          endpoint: values.endpoint || 'opc.tcp://localhost:4840',
        });
        break;
    }

    const submitData = {
      channelId: values.channelId,
      channelName: values.channelName,
      protocolType: values.protocolType,
      direction: values.direction,
      autoConnect: values.autoConnect || false,
      connectionConfig,
      ...(!editing && currentBusinessId ? { businessId: currentBusinessId } : {}),
    };

    try {
      if (editing) {
        await updateChannel(editing.channelId, submitData);
        message.success('已更新');
      } else {
        await createChannel(submitData);
        message.success('已创建');
      }
      setModalOpen(false);
    } catch {
      // 错误信息已通过 store.error 展示；保持弹窗打开
    }
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
          <Popconfirm
            title="删除该通道？"
            description="仅剩该通道绑定的测点会一并删除。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => handleDelete(record.channelId)}
          >
            <Button size="small" icon={<DeleteOutlined />} danger />
          </Popconfirm>
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
        <Space>
          <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
            导入通道配置
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) {
                handleImport(file);
                e.target.value = '';
              }
            }}
          />
          <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd} disabled={!currentBusinessId}>
            添加通道
          </Button>
        </Space>
      </div>
      {!currentBusinessId && businessesLoaded ? (
        <div style={{ textAlign: 'center', padding: 48, color: '#999' }}>
          暂无可用业务，请先在「业务管理」中创建业务
        </div>
      ) : (
        <Table columns={columns} dataSource={channels} rowKey="channelId" loading={loading} />
      )}

      <Modal
        title={editing ? '编辑通道' : '添加通道'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="channelId" label="通道ID" rules={[{ required: true }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="channelName" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="protocolType" label="协议" rules={[{ required: true }]}>
            <Select
              options={protocolOptions}
              onChange={(value) => setProtocolType(value)}
            />
          </Form.Item>
          <Form.Item name="direction" label="方向" rules={[{ required: true }]}>
            <Select options={directionOptions} />
          </Form.Item>

          {/* 自定义 TCP / Modbus TCP 配置 */}
          {(protocolType === 'CUSTOM_TCP' || protocolType === 'MODBUS_TCP') && (
            <>
              <Form.Item name="host" label="主机地址" rules={[{ required: true }]}>
                <Input placeholder="localhost" />
              </Form.Item>
              <Form.Item name="port" label="端口" rules={[{ required: true }]}>
                <InputNumber
                  style={{ width: '100%' }}
                  min={1}
                  max={65535}
                  placeholder={protocolType === 'MODBUS_TCP' ? '502' : '9002'}
                />
              </Form.Item>
              {protocolType === 'MODBUS_TCP' && (
                <Form.Item name="unitId" label="Unit ID">
                  <InputNumber style={{ width: '100%' }} min={1} max={255} placeholder="1" />
                </Form.Item>
              )}
            </>
          )}

          {/* MQTT 配置 */}
          {protocolType === 'MQTT' && (
            <>
              <Form.Item name="broker" label="Broker 地址" rules={[{ required: true }]}>
                <Input placeholder="tcp://localhost:1883" />
              </Form.Item>
              <Form.Item name="clientId" label="客户端ID">
                <Input placeholder="自动生成（留空）" />
              </Form.Item>
              <Form.Item name="username" label="用户名">
                <Input placeholder="可选" />
              </Form.Item>
              <Form.Item name="password" label="密码">
                <Input.Password placeholder="可选" />
              </Form.Item>
            </>
          )}

          {/* OPC-UA 配置 */}
          {protocolType === 'OPCUA' && (
            <Form.Item name="endpoint" label="端点地址" rules={[{ required: true }]}>
              <Input placeholder="opc.tcp://localhost:4840" />
            </Form.Item>
          )}

          <Form.Item name="autoConnect" label="自动连接" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
