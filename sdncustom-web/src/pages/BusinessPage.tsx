import { useEffect, useState } from 'react';
import { Button, Form, Input, message, Modal, Popconfirm, Space, Table, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { BusinessSystem } from '../types';
import { useBusinessStore } from '../stores/businessStore';

export default function BusinessPage() {
  const businesses = useBusinessStore((s) => s.businesses);
  const loading = useBusinessStore((s) => s.loading);
  const createBusiness = useBusinessStore((s) => s.createBusiness);
  const updateBusiness = useBusinessStore((s) => s.updateBusiness);
  const deleteBusiness = useBusinessStore((s) => s.deleteBusiness);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<BusinessSystem | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    useBusinessStore.getState().fetchBusinesses();
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (biz: BusinessSystem) => {
    setEditing(biz);
    form.setFieldsValue({
      businessId: biz.businessId,
      businessName: biz.businessName,
      description: biz.description,
    });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editing) {
        await updateBusiness(editing.businessId, {
          businessId: editing.businessId,
          businessName: values.businessName,
          description: values.description,
        });
        message.success('业务已更新');
      } else {
        await createBusiness(values);
        message.success('业务已创建');
      }
      setModalOpen(false);
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (biz: BusinessSystem) => {
    try {
      await deleteBusiness(biz.businessId);
      message.success('业务已删除');
    } catch (e) {
      if (e instanceof Error) message.error(e.message);
    }
  };

  const columns = [
    { title: '业务ID', dataIndex: 'businessId', key: 'businessId', width: 180 },
    { title: '名称', dataIndex: 'businessName', key: 'businessName', width: 200 },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 200 },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_: unknown, biz: BusinessSystem) => (
        <Space>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(biz)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该业务？"
            description="名下存在通道或测点时将无法删除"
            onConfirm={() => handleDelete(biz)}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
        <Typography.Title level={4} style={{ margin: 0 }}>
          业务管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建业务
        </Button>
      </div>
      <Table
        rowKey="businessId"
        columns={columns}
        dataSource={businesses}
        loading={loading}
        pagination={false}
      />
      <Modal
        title={editing ? '编辑业务' : '新建业务'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="businessId"
            label="业务ID"
            rules={[
              { required: true, message: '请输入业务ID' },
              { pattern: /^[a-zA-Z0-9_-]+$/, message: '仅支持字母、数字、下划线和短横线' },
            ]}
          >
            <Input placeholder="如 factory_a" disabled={!!editing} />
          </Form.Item>
          <Form.Item
            name="businessName"
            label="名称"
            rules={[{ required: true, message: '请输入业务名称' }]}
          >
            <Input placeholder="如 一号工厂" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
