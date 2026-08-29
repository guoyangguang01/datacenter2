import { useEffect, useState, useRef } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, Space, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { usePointStore } from '../stores/pointStore';
import { useChannelStore } from '../stores/channelStore';
import { pointApi } from '../services/api';
import type { MeasurementPoint } from '../types';

const dataTypeOptions = [
  { label: 'BOOL', value: 'BOOL' },
  { label: 'INT16', value: 'INT16' },
  { label: 'INT32', value: 'INT32' },
  { label: 'FLOAT32', value: 'FLOAT32' },
  { label: 'FLOAT64', value: 'FLOAT64' },
  { label: 'STRING', value: 'STRING' },
];

export default function PointPage() {
  const { points, loading, error, fetchPoints, createPoint, updatePoint, deletePoint, importPoints } = usePointStore();
  const { channels, fetchChannels } = useChannelStore();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<MeasurementPoint | null>(null);
  const [filterChannel, setFilterChannel] = useState<string | undefined>();
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  // 挂载时按当前筛选（默认全部）加载测点；筛选变化时重新加载
  useEffect(() => {
    fetchPoints(filterChannel);
  }, [filterChannel, fetchPoints]);

  // 展示 store 中的错误信息
  useEffect(() => {
    if (error) message.error(error);
  }, [error]);

  const handleAdd = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const handleEdit = (record: MeasurementPoint) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await deletePoint(id);
      message.success('已删除');
    } catch {
      // 错误信息已通过 store.error 展示
    }
  };

  const handleExport = async () => {
    try {
      const res = await pointApi.exportPoints();
      const blob = new Blob([res.data as BlobPart], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `points_export_${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('导出成功');
    } catch {
      message.error('导出失败');
    }
  };

  const handleImport = async (file: File) => {
    try {
      const text = await file.text();
      const data = JSON.parse(text);
      const count = await importPoints(Array.isArray(data) ? data : [data]);
      message.success(`导入成功，共导入 ${count} 个测点`);
    } catch {
      if (!usePointStore.getState().error) {
        message.error('导入失败，请检查文件格式');
      }
    }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    try {
      if (editing) {
        await updatePoint(editing.pointId, values);
        message.success('已更新');
      } else {
        await createPoint(values);
        message.success('已创建');
      }
      setModalOpen(false);
    } catch {
      // 错误信息已通过 store.error 展示；保持弹窗打开
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'pointId', key: 'pointId' },
    { title: '名称', dataIndex: 'pointName', key: 'pointName' },
    { title: '通道', dataIndex: 'channelId', key: 'channelId' },
    { title: '地址', dataIndex: 'address', key: 'address' },
    { title: '类型', dataIndex: 'dataType', key: 'dataType' },
    { title: '单位', dataIndex: 'unit', key: 'unit' },
    { title: '死区', dataIndex: 'deadband', key: 'deadband', render: (v: number | null | undefined) => v != null ? v : '-' },
    { title: '可写', dataIndex: 'writable', key: 'writable', render: (v: boolean) => v ? '是' : '否' },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: MeasurementPoint) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => handleEdit(record)} />
          <Button size="small" icon={<DeleteOutlined />} danger onClick={() => handleDelete(record.pointId)} />
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between' }}>
        <Space>
          <h2>测点管理</h2>
          <Select
            placeholder="按通道筛选"
            allowClear
            style={{ width: 200 }}
            onChange={(v) => setFilterChannel(v)}
            options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))}
          />
        </Space>
        <Space>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>
            导出
          </Button>
          <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
            导入
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
          <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
            添加测点
          </Button>
        </Space>
      </div>
      <Table columns={columns} dataSource={points} rowKey="pointId" loading={loading} />

      <Modal
        title={editing ? '编辑测点' : '添加测点'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="pointId" label="测点ID" rules={[{ required: true }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="pointName" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="channelId" label="通道" rules={[{ required: true }]}>
            <Select options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))} />
          </Form.Item>
          <Form.Item name="address" label="地址" rules={[{ required: true }]}>
            <Input placeholder="例如 40001 或 sensors/temp01" />
          </Form.Item>
          <Form.Item name="dataType" label="数据类型" rules={[{ required: true }]}>
            <Select options={dataTypeOptions} />
          </Form.Item>
          <Form.Item name="unit" label="单位">
            <Input placeholder="例如 °C, Pa, %" />
          </Form.Item>
          <Form.Item name="deadband" label="死区" tooltip="数值变化超过死区才上报历史与推送；0 表示任何变化都上报">
            <InputNumber min={0} step={0.01} placeholder="0 = 任何变化都上报" style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="writable" label="可写" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
