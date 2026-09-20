import { useEffect, useState, useRef } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Select, Tag, Space, message, Popconfirm } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, DownloadOutlined, UploadOutlined } from '@ant-design/icons';
import { usePointStore } from '../stores/pointStore';
import { useChannelStore } from '../stores/channelStore';
import { useBusinessStore } from '../stores/businessStore';
import { pointApi } from '../services/api';
import type { DataExportPayload, MeasurementPoint, PointDirection } from '../types';

const dataTypeOptions = [
  { label: 'BOOL', value: 'BOOL' },
  { label: 'INT16', value: 'INT16' },
  { label: 'INT32', value: 'INT32' },
  { label: 'FLOAT32', value: 'FLOAT32' },
  { label: 'FLOAT64', value: 'FLOAT64' },
  { label: 'STRING', value: 'STRING' },
];

export default function PointPage() {
  const { points, loading, error, clearError, fetchPoints, createPoint, updatePoint, deletePoint, exportData, exportCsv, importData, importCsv } = usePointStore();
  const { channels, fetchChannels } = useChannelStore();
  const currentBusinessId = useBusinessStore((s) => s.currentBusinessId);
  const businessesLoaded = useBusinessStore((s) => s.businesses.length > 0);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<MeasurementPoint | null>(null);
  const [filterChannel, setFilterChannel] = useState<string | undefined>();
  const [filterDirection, setFilterDirection] = useState<PointDirection | undefined>();
  const [allPoints, setAllPoints] = useState<MeasurementPoint[]>([]);
  const [form] = Form.useForm();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const csvInputRef = useRef<HTMLInputElement>(null);
  const watchedDirection = Form.useWatch('direction', form);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels, currentBusinessId]);

  // 挂载时按当前筛选（默认全部）加载测点；筛选或业务变化时重新加载
  useEffect(() => {
    fetchPoints(filterChannel, undefined, filterDirection);
  }, [filterChannel, filterDirection, fetchPoints, currentBusinessId]);

  // 引用标注要按 pointId 查被引用的输出测点，必须用当前业务的**完整**列表
  useEffect(() => {
    pointApi
      .getAll(undefined, currentBusinessId ?? undefined)
      .then((res) => setAllPoints(res.data.data ?? []))
      .catch(() => setAllPoints([]));
  }, [currentBusinessId]);

  // 展示 store 中的错误信息；弹出后立即清空，否则重新进入本页会重复弹同一条
  useEffect(() => {
    if (error) {
      message.error(error);
      clearError();
    }
  }, [error, clearError]);

  const showCreateModal = async () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ channelId: filterChannel, direction: 'OUTPUT' });
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
    form.setFieldsValue({
      pointId: record.pointId,
      pointName: record.pointName,
      channelId: record.channelId,
      address: record.address,
      dataType: record.dataType,
      unit: record.unit,
      deadband: record.deadband,
      direction: record.direction,
      referencePointId: record.referencePointId,
    });
    setModalOpen(true);
  };

  const handleDelete = async (id: string) => {
    try {
      await deletePoint(id);
      message.success('已删除');
      fetchPoints(filterChannel, undefined, filterDirection);
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

  // 导出当前筛选后的测点：store.points 已经是「当前业务 + 通道 + 方向」的结果，
  // 与「导入 CSV 必须先选通道」对称——这样导出的文件能直接再导入回该通道
  const handleExportCsv = () => {
    exportCsv();
    message.success(`已导出 ${points.length} 个测点`);
  };

  const handleImportJson = async (file: File) => {
    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      const payload: DataExportPayload = Array.isArray(parsed) ? { points: parsed } : parsed;
      if (currentBusinessId) {
        payload.points = payload.points?.map((p) =>
          p.businessId ? p : { ...p, businessId: currentBusinessId }
        );
      }
      const count = await importData(payload);
      message.success(`导入成功，共导入 ${count} 个测点`);
    } catch {
      if (!usePointStore.getState().error) {
        message.error('导入失败，请检查 JSON 文件格式');
      }
    }
  };

  const handleImportCsv = async (file: File) => {
    if (!currentBusinessId) {
      message.warning('请先选择业务');
      return;
    }
    if (!filterChannel) {
      message.warning('请先在上方「按通道筛选」中选择目标通道，再导入 CSV');
      return;
    }
    try {
      const count = await importCsv(file, currentBusinessId, filterChannel);
      message.success(`导入成功，共导入 ${count} 个测点`);
      fetchPoints(filterChannel, undefined, filterDirection);
    } catch {
      if (!usePointStore.getState().error) {
        message.error('导入失败，请检查 CSV 文件格式');
      }
    }
  };

  const handleSubmit = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    try {
      if (editing) {
        await updatePoint(editing.pointId, {
          pointId: editing.pointId,
          pointName: values.pointName,
          channelId: values.channelId,
          address: values.address,
          dataType: values.dataType,
          unit: values.unit,
          deadband: values.deadband,
        });
        message.success('已更新');
      } else {
        await createPoint({
          pointId: values.pointId,
          businessId: currentBusinessId ?? undefined,
          pointName: values.pointName,
          channelId: values.channelId,
          address: values.address,
          dataType: values.dataType,
          unit: values.unit,
          deadband: values.deadband,
          direction: values.direction,
          referencePointId: values.direction === 'INPUT' ? values.referencePointId : undefined,
        });
        message.success('已创建');
      }
      setModalOpen(false);
      fetchPoints(filterChannel, undefined, filterDirection);
    } catch {
      // 错误信息已通过 store.error 展示；保持弹窗打开
    }
  };

  // 输入测点引用的输出测点候选：列出所有 OUTPUT 测点
  const referenceOptions = allPoints
    .filter((p) => p.direction === 'OUTPUT')
    .map((p) => ({
      label: `${p.pointName} (${p.pointId}) · ${p.dataType} · ${p.channelId}`,
      value: p.pointId,
    }));

  // 列表里「引用测点」列显示的名称：优先取被引用测点的名称，
  // 查不到（不在当前业务全量列表里）就回退显示 ID
  const referenceName = (pointId: string) =>
    allPoints.find((p) => p.pointId === pointId)?.pointName ?? pointId;

  // 编辑输入测点时展示其引用。被引用的测点若不在 allPoints 里，补一条回退项，否则下拉框显示空白
  const referenceOptionsFor = (referencePointId: string | undefined) =>
    referencePointId && !referenceOptions.some((o) => o.value === referencePointId)
      ? [...referenceOptions, { label: referencePointId, value: referencePointId }]
      : referenceOptions;

  const columns = [
    { title: 'ID', dataIndex: 'pointId', key: 'pointId' },
    { title: '名称', dataIndex: 'pointName', key: 'pointName' },
    { title: '所属通道', dataIndex: 'channelId', key: 'channelId' },
    { title: '地址', dataIndex: 'address', key: 'address' },
    { title: '类型', dataIndex: 'dataType', key: 'dataType' },
    { title: '单位', dataIndex: 'unit', key: 'unit' },
    { title: '死区', dataIndex: 'deadband', key: 'deadband', render: (v: number | null | undefined) => v != null ? v : '-' },
    {
      title: '方向',
      dataIndex: 'direction',
      key: 'direction',
      render: (v: PointDirection | null | undefined) =>
        !v ? '-' : <Tag color={v === 'OUTPUT' ? 'green' : 'blue'}>{v === 'OUTPUT' ? '输出' : '输入'}</Tag>,
    },
    {
      title: '引用测点',
      dataIndex: 'referencePointId',
      key: 'referencePointId',
      render: (v: string | undefined) => (v ? referenceName(v) : '-'),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: unknown, record: MeasurementPoint) => (
        <Space>
          <Button size="small" icon={<EditOutlined />} onClick={() => showEditModal(record)} />
          <Popconfirm
            title="删除该测点？"
            description="其缓存值会一并清除。"
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
          <Select
            placeholder="按方向筛选"
            allowClear
            style={{ width: 160 }}
            onChange={(v) => setFilterDirection(v)}
            options={[
              { label: '输入测点', value: 'INPUT' },
              { label: '输出测点', value: 'OUTPUT' },
            ]}
          />
        </Space>
        <Space>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>
            导出数据
          </Button>
          <Button icon={<DownloadOutlined />} onClick={handleExportCsv} disabled={points.length === 0}>
            导出 CSV
          </Button>
          <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()}>
            导入 JSON
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) {
                handleImportJson(file);
                e.target.value = '';
              }
            }}
          />
          <Button icon={<UploadOutlined />} onClick={() => csvInputRef.current?.click()}>
            导入 CSV
          </Button>
          <input
            ref={csvInputRef}
            type="file"
            accept=".csv"
            style={{ display: 'none' }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) {
                handleImportCsv(file);
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
          {!editing && (
            <Form.Item name="direction" label="方向" rules={[{ required: true }]}>
              <Select
                options={[
                  { label: '输出测点（从外部采集）', value: 'OUTPUT' },
                  { label: '输入测点（写出到外部）', value: 'INPUT' },
                ]}
              />
            </Form.Item>
          )}
          {editing && editing.direction === 'INPUT' && (
            <Form.Item label="引用的输出测点" tooltip="方向与引用创建后不可变更">
              <Select value={editing.referencePointId} disabled options={referenceOptionsFor(editing.referencePointId)} />
            </Form.Item>
          )}
          <Form.Item name="channelId" label="所属通道" rules={[{ required: true }]}>
            <Select options={channels.map((c) => ({ label: c.channelName, value: c.channelId }))} />
          </Form.Item>
          <Form.Item name="address" label="地址" rules={[{ required: true }]}>
            <Input placeholder="例如 40001 或 sensors/temp01" />
          </Form.Item>

          {watchedDirection === 'INPUT' && !editing && (
            <Form.Item
              name="referencePointId"
              label="引用的输出测点"
              rules={[{ required: true, message: '请选择引用的输出测点' }]}
            >
              <Select
                placeholder="选择要引用的输出测点"
                showSearch
                optionFilterProp="label"
                options={referenceOptions}
              />
            </Form.Item>
          )}

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
        </Form>
      </Modal>
    </div>
  );
}
