import { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, Select, InputNumber, Switch, Space, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { usePointStore } from '../stores/pointStore';
import { useChannelStore } from '../stores/channelStore';
import type { MeasurementPoint, PointDataType } from '../types';

const dataTypeOptions = [
  { label: 'BOOL', value: 'BOOL' },
  { label: 'INT16', value: 'INT16' },
  { label: 'INT32', value: 'INT32' },
  { label: 'FLOAT32', value: 'FLOAT32' },
  { label: 'FLOAT64', value: 'FLOAT64' },
  { label: 'STRING', value: 'STRING' },
];

export default function PointPage() {
  const { points, loading, fetchPoints, createPoint, updatePoint, deletePoint } = usePointStore();
  const { channels, fetchChannels } = useChannelStore();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<MeasurementPoint | null>(null);
  const [filterChannel, setFilterChannel] = useState<string | undefined>();
  const [form] = Form.useForm();

  useEffect(() => {
    fetchChannels();
    fetchPoints();
  }, [fetchChannels, fetchPoints]);

  useEffect(() => {
    fetchPoints(filterChannel);
  }, [filterChannel, fetchPoints]);

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
    await deletePoint(id);
    message.success('Deleted');
  };

  const handleSubmit = async () => {
    const values = await form.validateFields();
    if (editing) {
      await updatePoint(editing.pointId, values);
      message.success('Updated');
    } else {
      await createPoint(values);
      message.success('Created');
    }
    setModalOpen(false);
  };

  const columns = [
    { title: 'ID', dataIndex: 'pointId', key: 'pointId' },
    { title: 'Name', dataIndex: 'pointName', key: 'pointName' },
    { title: 'Channel', dataIndex: 'channelId', key: 'channelId' },
    { title: 'Address', dataIndex: 'address', key: 'address' },
    { title: 'Type', dataIndex: 'dataType', key: 'dataType' },
    { title: 'Unit', dataIndex: 'unit', key: 'unit' },
    { title: 'Writable', dataIndex: 'writable', key: 'writable', render: (v: boolean) => v ? 'Yes' : 'No' },
    {
      title: 'Actions',
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
          <h2>Measurement Points</h2>
          <Select
            placeholder="Filter by Channel"
            allowClear
            style={{ width: 200 }}
            onChange={(v) => setFilterChannel(v)}
            options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))}
          />
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={handleAdd}>
          Add Point
        </Button>
      </div>
      <Table columns={columns} dataSource={points} rowKey="pointId" loading={loading} />

      <Modal
        title={editing ? 'Edit Point' : 'Add Point'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        width={600}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="pointId" label="Point ID" rules={[{ required: true }]}>
            <Input disabled={!!editing} />
          </Form.Item>
          <Form.Item name="pointName" label="Name" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="channelId" label="Channel" rules={[{ required: true }]}>
            <Select options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))} />
          </Form.Item>
          <Form.Item name="address" label="Address" rules={[{ required: true }]}>
            <Input placeholder="e.g. 40001 or sensors/temp01" />
          </Form.Item>
          <Form.Item name="dataType" label="Data Type" rules={[{ required: true }]}>
            <Select options={dataTypeOptions} />
          </Form.Item>
          <Form.Item name="unit" label="Unit">
            <Input placeholder="e.g. °C, Pa, %" />
          </Form.Item>
          <Space>
            <Form.Item name="scaleFactor" label="Scale Factor" initialValue={1.0}>
              <InputNumber />
            </Form.Item>
            <Form.Item name="offset" label="Offset" initialValue={0.0}>
              <InputNumber />
            </Form.Item>
            <Form.Item name="deadBand" label="Dead Band" initialValue={0.0}>
              <InputNumber />
            </Form.Item>
          </Space>
          <Form.Item name="writable" label="Writable" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
