import { useEffect, useState, useRef } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Switch, Space, message, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, DownloadOutlined, UploadOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { usePointStore } from '../stores/pointStore';
import { useChannelStore } from '../stores/channelStore';
import { useBusinessStore } from '../stores/businessStore';
import { pointApi } from '../services/api';
import type { DataExportPayload, MeasurementPoint } from '../types';

const dataTypeOptions = [
  { label: 'BOOL', value: 'BOOL' },
  { label: 'INT16', value: 'INT16' },
  { label: 'INT32', value: 'INT32' },
  { label: 'FLOAT32', value: 'FLOAT32' },
  { label: 'FLOAT64', value: 'FLOAT64' },
  { label: 'STRING', value: 'STRING' },
];

export default function PointPage() {
  const { points, loading, error, clearError, fetchPoints, createPoint, updatePoint, deletePoint, exportData, importData } = usePointStore();
  const { channels, fetchChannels } = useChannelStore();
  const currentBusinessId = useBusinessStore((s) => s.currentBusinessId);
  const businessesLoaded = useBusinessStore((s) => s.businesses.length > 0);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<MeasurementPoint | null>(null);
  const [filterChannel, setFilterChannel] = useState<string | undefined>();
  const [linkPointId, setLinkPointId] = useState<string | undefined>();
  const [allPoints, setAllPoints] = useState<MeasurementPoint[]>([]);
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const mainChannelId = Form.useWatch('channelId', form);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels, currentBusinessId]);

  // 挂载时按当前筛选（默认全部）加载测点；筛选或业务变化时重新加载
  useEffect(() => {
    fetchPoints(filterChannel);
  }, [filterChannel, fetchPoints, currentBusinessId]);

  // 展示 store 中的错误信息；弹出后立即清空，否则重新进入本页会重复弹同一条
  useEffect(() => {
    if (error) {
      message.error(error);
      clearError();
    }
  }, [error, clearError]);

  const showCreateModal = async () => {
    setEditing(null);
    setLinkPointId(undefined);
    form.resetFields();
    form.setFieldsValue({ channelId: filterChannel, writable: false });
    try {
      const res = await pointApi.getAll(undefined, currentBusinessId ?? undefined);
      setAllPoints(res.data.data ?? []);
    } catch {
      setAllPoints([]);
    }
    setModalOpen(true);
  };

  const showEditModal = (record: MeasurementPoint) => {
    setEditing(record);
    setLinkPointId(undefined);
    const b = record.bindings ?? [];
    form.setFieldsValue({
      pointId: record.pointId,
      pointName: record.pointName,
      channelId: b[0]?.channelId,
      address: b[0]?.address,
      dataType: record.dataType,
      unit: record.unit,
      deadband: record.deadband,
      writable: record.writable,
      additionalBindings: b.slice(1).map((x) => ({ channelId: x.channelId, address: x.address })),
    });
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
      await exportData();
      message.success('导出成功');
    } catch {
      message.error('导出失败');
    }
  };

  const handleImport = async (file: File) => {
    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      const payload: DataExportPayload = Array.isArray(parsed) ? { points: parsed } : parsed;
      // 文件里没写归属的测点落到当前业务（不写则后端落 default 业务）
      if (currentBusinessId) {
        payload.points = payload.points?.map((p) =>
          p.businessId ? p : { ...p, businessId: currentBusinessId }
        );
      }
      const count = await importData(payload);
      message.success(`导入成功，共导入 ${count} 个测点`);
    } catch {
      if (!usePointStore.getState().error) {
        message.error('导入失败，请检查文件格式');
      }
    }
  };

  const handleSubmit = async () => {
    // 校验失败时 antd 已在表单上标红；这里吞掉 rejection，避免未处理的 promise
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    try {
      if (editing) {
        const bindings = [
          { channelId: values.channelId, address: values.address },
          ...(values.additionalBindings ?? []),
        ];
        await updatePoint(editing.pointId, {
          pointId: editing.pointId,
          pointName: values.pointName,
          dataType: values.dataType,
          unit: values.unit,
          deadband: values.deadband,
          writable: values.writable,
          bindings,
        });
        message.success('已更新');
      } else if (linkPointId) {
        // 关联既有测点：给该测点加一条绑定，不新建测点
        await pointApi.addBinding(linkPointId, { channelId: values.channelId, address: values.address });
        message.success(`已关联到测点 ${linkPointId}`);
      } else {
        await createPoint({
          pointId: values.pointId,
          businessId: currentBusinessId ?? undefined,
          pointName: values.pointName,
          dataType: values.dataType,
          unit: values.unit,
          deadband: values.deadband,
          writable: values.writable,
          bindings: [{ channelId: values.channelId, address: values.address }],
        });
        message.success('已创建');
      }
      setModalOpen(false);
      fetchPoints(filterChannel);
    } catch {
      // 错误信息已通过 store.error 展示；保持弹窗打开
    }
  };

  // 关联选择器：排除已绑定当前所属通道的点
  const linkOptions = allPoints
    .filter((p) => p.pointId !== editing?.pointId)
    .filter((p) => !(p.bindings ?? []).some((x) => x.channelId === mainChannelId))
    .map((p) => ({ label: `${p.pointName} (${p.pointId})`, value: p.pointId }));

  // 创建 + 已选关联 → 不新建测点，隐藏元数据字段
  const showMetadata = editing ? true : !linkPointId;

  const columns = [
    { title: 'ID', dataIndex: 'pointId', key: 'pointId' },
    { title: '名称', dataIndex: 'pointName', key: 'pointName' },
    {
      title: '所属通道',
      key: 'channelId',
      render: (_: unknown, r: MeasurementPoint) => r.bindings?.[0]?.channelId ?? '-',
    },
    {
      title: '地址',
      key: 'address',
      render: (_: unknown, r: MeasurementPoint) => r.bindings?.[0]?.address ?? '-',
    },
    { title: '类型', dataIndex: 'dataType', key: 'dataType' },
    { title: '单位', dataIndex: 'unit', key: 'unit' },
    { title: '死区', dataIndex: 'deadband', key: 'deadband', render: (v: number | null | undefined) => v != null ? v : '-' },
    { title: '可写', dataIndex: 'writable', key: 'writable', render: (v: boolean) => v ? '是' : '否' },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: MeasurementPoint) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => showEditModal(record)} />
          <Popconfirm
            title="删除该测点？"
            description="其绑定关系与缓存值会一并清除。"
            okText="删除"
            cancelText="取消"
            onConfirm={() => handleDelete(record.pointId)}
          >
            <Button size="small" icon={<DeleteOutlined />} danger />
          </Popconfirm>
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
            导出数据
          </Button>
          <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
            导入数据
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
          <Button type="primary" icon={<PlusOutlined />} onClick={showCreateModal} disabled={!currentBusinessId}>
            添加测点
          </Button>
        </Space>
      </div>
      {!currentBusinessId && businessesLoaded ? (
        <div style={{ textAlign: 'center', padding: 48, color: '#999' }}>
          暂无可用业务，请先在「业务管理」中创建业务
        </div>
      ) : (
        <Table columns={columns} dataSource={points} rowKey="pointId" loading={loading} />
      )}

      <Modal
        title={editing ? '编辑测点' : '添加测点'}
        open={modalOpen}
        onOk={handleSubmit}
        onCancel={() => setModalOpen(false)}
        width={720}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="channelId" label="所属通道" rules={[{ required: true }]}>
            <Select options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))} />
          </Form.Item>
          <Form.Item name="address" label="地址" rules={[{ required: true }]}>
            <Input placeholder="例如 40001 或 sensors/temp01" />
          </Form.Item>

          {!editing && (
            <Form.Item name="linkPointId" label="关联既有测点（可选）" tooltip="选中后不新建测点，把上面的通道:地址作为绑定附加到该测点">
              <Select
                allowClear
                placeholder="选择其它通道的既有测点进行关联"
                onChange={(v) => setLinkPointId(v)}
                options={linkOptions}
                showSearch
                optionFilterProp="label"
              />
            </Form.Item>
          )}
          {!editing && linkPointId && (
            <div style={{ marginBottom: 16, color: '#fa8c16' }}>
              将把 [{mainChannelId ?? '-'}:{form.getFieldValue('address')}] 作为绑定附加到测点 {linkPointId}，不再新建测点。
            </div>
          )}

          {showMetadata && (
            <>
              <Form.Item name="pointId" label="测点ID" rules={[{ required: true }]}>
                <Input disabled={!!editing} />
              </Form.Item>
              <Form.Item name="pointName" label="名称" rules={[{ required: true }]}>
                <Input />
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
            </>
          )}

          {editing && (
            <Form.List name="additionalBindings">
              {(fields, { add, remove }) => (
                <>
                  {fields.map(({ key, name, ...restField }) => (
                    <Space key={key} align="baseline" style={{ display: 'flex', marginBottom: 8 }}>
                      <Form.Item
                        {...restField}
                        name={[name, 'channelId']}
                        rules={[{ required: true, message: '选择来源通道' }]}
                        style={{ marginBottom: 0 }}
                      >
                        <Select
                          placeholder="来源通道"
                          style={{ width: 190 }}
                          options={channels
                            .filter((c) => c.channelId !== mainChannelId)
                            .map((c) => ({ label: c.channelName, value: c.channelId }))}
                        />
                      </Form.Item>
                      <Form.Item
                        {...restField}
                        name={[name, 'address']}
                        rules={[{ required: true, message: '输入来源地址' }]}
                        style={{ marginBottom: 0 }}
                      >
                        <Input placeholder="来源地址" style={{ width: 220 }} />
                      </Form.Item>
                      <MinusCircleOutlined onClick={() => remove(name)} />
                    </Space>
                  ))}
                  <Form.Item>
                    <Button type="dashed" onClick={() => add({ channelId: undefined, address: '' })} block icon={<PlusOutlined />}>
                      添加附加来源
                    </Button>
                  </Form.Item>
                </>
              )}
            </Form.List>
          )}
        </Form>
      </Modal>
    </div>
  );
}
