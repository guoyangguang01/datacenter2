package com.sdncustom.protocol.mock;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.UaNodeManager;
import org.eclipse.milo.opcua.sdk.server.api.AddressSpaceFilter;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.api.services.AttributeServices;
import org.eclipse.milo.opcua.sdk.server.api.services.ViewServices;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * OPC-UA 模拟服务器（基于 Eclipse Milo Server SDK）
 * 提供完整的 OPC-UA 协议支持
 */
@Slf4j
public class MockOpcUaServer {

    private final int port;
    private OpcUaServer server;
    private MockNamespace namespace;
    private volatile boolean running = false;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Random random = new Random();

    // 节点值缓存: nodeId -> value
    private final Map<String, Object> nodeValues = new LinkedHashMap<>();

    public MockOpcUaServer(int port) {
        this.port = port;
        initMockData();
    }

    /**
     * 从 mock-data.json 加载 OPC-UA 通道的测点地址，自动注册为节点。
     * 每个地址按 dataType 生成合适的初始值。
     */
    public void loadFromMockData(String mockDataPath) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> data = om.readValue(new File(mockDataPath), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) data.get("points");
            if (points == null) return;

            for (Map<String, Object> p : points) {
                String channelId = String.valueOf(p.getOrDefault("channelId", ""));
                if (!"ch_opcua_mock".equals(channelId)) continue;
                String address = String.valueOf(p.getOrDefault("address", ""));
                String dataType = String.valueOf(p.getOrDefault("dataType", "FLOAT64"));
                if (address.isEmpty()) continue;
                // 已在 initMockData 中注册的跳过
                if (nodeValues.containsKey(address)) continue;
                nodeValues.put(address, defaultValueFor(dataType));
            }
            log.info("Loaded {} extra nodes from {}", nodeValues.size(), mockDataPath);
        } catch (IOException e) {
            log.warn("Failed to load mock-data.json: {}", e.getMessage());
        }
    }

    private static Object defaultValueFor(String dataType) {
        return switch (dataType) {
            case "BOOL" -> false;
            case "INT16" -> (short) 0;
            case "INT32" -> 0;
            case "FLOAT32" -> 0.0f;
            case "FLOAT64" -> 0.0;
            case "STRING" -> "";
            default -> 0.0;
        };
    }

    /**
     * 初始化：所有节点从 mock-data.json 加载，不再硬编码。
     */
    private void initMockData() {
        String mockDataPath = findMockDataPath();
        if (mockDataPath != null) {
            loadFromMockData(mockDataPath);
        }
        if (nodeValues.isEmpty()) {
            log.warn("No nodes loaded (mock-data.json not found or has no OPC-UA points)");
        }
    }

    /**
     * 启动模拟服务器
     */
    public void start() {
        try {
            // 配置端点
            EndpointConfiguration endpoint = EndpointConfiguration.newBuilder()
                    .setBindAddress("0.0.0.0")
                    .setBindPort(port)
                    .setHostname("localhost")
                    .setPath("")
                    .build();

            OpcUaServerConfig config = OpcUaServerConfig.builder()
                    .setApplicationName(LocalizedText.english("SDNCustom Mock OPC-UA Server"))
                    .setApplicationUri("urn:sdncustom:mock:opcua:server")
                    .setProductUri("urn:sdncustom:mock")
                    .setEndpoints(Set.of(endpoint))
                    .setBuildInfo(new BuildInfo(
                            "urn:sdncustom:mock:opcua:server",
                            "SDNCustom",
                            "Mock OPC-UA Server for testing",
                            "1.0.0",
                            "",
                            DateTime.now()
                    ))
                    .build();

            server = new OpcUaServer(config);
            namespace = new MockNamespace(server, "urn:sdncustom:mock:opcua:namespace");
            namespace.startup();
            namespace.verifyNodes();
            server.startup().get();
            running = true;
            log.info("Mock OPC-UA Server started on port {}", port);

            // 定时更新模拟数据
            scheduler.scheduleAtFixedRate(this::updateMockData, 1, 2, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Failed to start Mock OPC-UA Server", e);
        }
    }

    /**
     * 停止模拟服务器
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        if (namespace != null) {
            try {
                namespace.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down Mock OPC-UA namespace", e);
            }
            namespace = null;
        }
        if (server != null) {
            try {
                server.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error stopping Mock OPC-UA Server", e);
            } finally {
                server = null;
            }
        }
        log.info("Mock OPC-UA Server stopped");
    }

    /**
     * 对所有数值节点施加随机波动，BOOL 节点随机切换。
     */
    private void updateMockData() {
        if (!running || namespace == null) return;

        try {
            for (Map.Entry<String, Object> entry : nodeValues.entrySet()) {
                String address = entry.getKey();
                Object current = entry.getValue();
                if (current instanceof Short s) {
                    updateNodeValue(address, (short) (s + random.nextInt(5) - 2));
                } else if (current instanceof Integer i) {
                    updateNodeValue(address, i + random.nextInt(5) - 2);
                } else if (current instanceof Float f) {
                    float amplitude = Math.max(Math.abs(f) * 0.02f, 0.5f);
                    updateNodeValue(address, f + (random.nextFloat() - 0.5f) * amplitude * 2);
                } else if (current instanceof Double d) {
                    double amplitude = Math.max(Math.abs(d) * 0.02, 0.5);
                    updateNodeValue(address, d + (random.nextDouble() - 0.5) * amplitude * 2);
                } else if (current instanceof Boolean) {
                    updateNodeValue(address, random.nextBoolean());
                }
                // String 节点不波动
            }
        } catch (Exception e) {
            log.error("Error updating mock data", e);
        }
    }

    /**
     * 更新节点值
     */
    private void updateNodeValue(String path, Object value) {
        nodeValues.put(path, value);
        namespace.updateVariableValue(path, value);
    }

    /**
     * 自定义命名空间
     */
    private class MockNamespace extends ManagedNamespaceWithLifecycle {

        private final Map<String, UaVariableNode> variableNodes = new HashMap<>();
        private final SubscriptionModel subscriptionModel;

        public MockNamespace(OpcUaServer server, String namespaceUri) {
            super(server, namespaceUri);
            subscriptionModel = new SubscriptionModel(server, this);
            getLifecycleManager().addLifecycle(subscriptionModel);
            // 注册启动任务，在启动时创建节点
            getLifecycleManager().addStartupTask(this::createNodes);
        }

        @Override
        public AddressSpaceFilter getFilter() {
            // 返回接受所有请求的过滤器
            return new AddressSpaceFilter() {
                @Override
                public boolean filterBrowse(OpcUaServer server, NodeId nodeId) { return true; }
                @Override
                public boolean filterRegisterNode(OpcUaServer server, NodeId nodeId) { return true; }
                @Override
                public boolean filterUnregisterNode(OpcUaServer server, NodeId nodeId) { return true; }
                @Override
                public boolean filterRead(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterWrite(OpcUaServer server, WriteValue writeValue) { return true; }
                @Override
                public boolean filterHistoryRead(OpcUaServer server, HistoryReadValueId historyReadValueId) { return true; }
                @Override
                public boolean filterHistoryUpdate(OpcUaServer server, HistoryUpdateDetails historyUpdateDetails) { return true; }
                @Override
                public boolean filterCall(OpcUaServer server, CallMethodRequest callMethodRequest) { return true; }
                @Override
                public boolean filterOnCreateDataItem(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnModifyDataItem(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnCreateEventItem(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnModifyEventItem(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnDataItemsCreated(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnDataItemsModified(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnDataItemsDeleted(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnEventItemsCreated(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnEventItemsModified(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnEventItemsDeleted(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterOnMonitoringModeChanged(OpcUaServer server, ReadValueId readValueId) { return true; }
                @Override
                public boolean filterAddNodes(OpcUaServer server, AddNodesItem addNodesItem) { return true; }
                @Override
                public boolean filterDeleteNodes(OpcUaServer server, DeleteNodesItem deleteNodesItem) { return true; }
                @Override
                public boolean filterAddReferences(OpcUaServer server, AddReferencesItem addReferencesItem) { return true; }
                @Override
                public boolean filterDeleteReferences(OpcUaServer server, DeleteReferencesItem deleteReferencesItem) { return true; }
            };
        }

        @Override
        public void onDataItemsCreated(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsCreated(dataItems);
        }

        @Override
        public void onDataItemsModified(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsModified(dataItems);
        }

        @Override
        public void onDataItemsDeleted(List<DataItem> dataItems) {
            subscriptionModel.onDataItemsDeleted(dataItems);
        }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
            subscriptionModel.onMonitoringModeChanged(monitoredItems);
        }

        @Override
        public void read(ReadContext context, Double maxAge, TimestampsToReturn timestamps, List<ReadValueId> readValueIds) {
            // 委托给父类实现，由 UaNodeManager 提供节点读取
            super.read(context, maxAge, timestamps, readValueIds);
        }

        @Override
        public void write(WriteContext context, List<WriteValue> writeValues) {
            // 委托给父类实现，由 UaNodeManager 提供节点写入
            super.write(context, writeValues);
        }

        @Override
        public void browse(BrowseContext context, ViewDescription viewDescription, NodeId nodeId) {
            // 委托给父类实现，由 UaNodeManager 提供引用浏览
            super.browse(context, viewDescription, nodeId);
        }

        @Override
        public void getReferences(BrowseContext context, ViewDescription viewDescription, NodeId nodeId) {
            // 委托给父类实现，由 UaNodeManager 提供引用获取
            super.getReferences(context, viewDescription, nodeId);
        }

        /**
         * 从 nodeValues（mock-data.json 加载）创建所有节点。
         * 地址格式如 "ns=2;s=MQTT/Temperature" 会按 OPC-UA 规范解析为 NodeId(2, "MQTT/Temperature")；
         * 简单路径（无 ns= 前缀）使用 mock server 自身的 namespace index。
         */
        private void createNodes() {
            UaNodeContext context = getNodeContext();
            UaNodeManager nodeManager = getNodeManager();

            for (Map.Entry<String, Object> entry : nodeValues.entrySet()) {
                String address = entry.getKey();
                Object value = entry.getValue();
                NodeId nodeId = parseAddressToNodeId(address);
                Variant variant = convertToVariant(value);
                NodeId dataType = getDataTypeNodeId(value);

                UaVariableNode variableNode = new UaVariableNode(
                        context,
                        nodeId,
                        new org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName(
                                nodeId.getNamespaceIndex().intValue(), nodeId.getIdentifier().toString()),
                        LocalizedText.english(nodeId.getIdentifier().toString())
                );
                variableNode.setValue(new DataValue(variant));
                variableNode.setDataType(dataType);
                variableNode.setAccessLevel(UByte.valueOf(3)); // READ_WRITE
                variableNode.setUserAccessLevel(UByte.valueOf(3));

                nodeManager.addNode(variableNode);
                variableNodes.put(address, variableNode);
            }

            log.info("Created {} mock nodes from mock-data.json (namespace index={})", variableNodes.size(), getNamespaceIndex());
        }

        /**
         * 解析 OPC-UA 地址为 NodeId：ns=2;s=XXX → NodeId(2, "XXX")；无前缀 → NodeId(mockNs, address)
         */
        private NodeId parseAddressToNodeId(String address) {
            int ns = getNamespaceIndex().intValue();
            String identifier = address;
            boolean isString = true;

            for (String part : address.split(";")) {
                part = part.trim();
                if (part.startsWith("ns=")) {
                    ns = Integer.parseInt(part.substring(3));
                } else if (part.startsWith("s=")) {
                    identifier = part.substring(2);
                    isString = true;
                } else if (part.startsWith("i=")) {
                    identifier = part.substring(2);
                    isString = false;
                }
            }

            if (isString) {
                return new NodeId(ns, identifier);
            } else {
                return new NodeId(ns, UInteger.valueOf(identifier));
            }
        }

        void verifyNodes() {
            int count = 0;
            for (String address : variableNodes.keySet()) {
                if (count++ >= 3) break;
                NodeId nid = parseAddressToNodeId(address);
                getNodeManager().getNode(nid).ifPresent(node -> {
                    if (node instanceof UaVariableNode) {
                        UaVariableNode vn = (UaVariableNode) node;
                        log.info("Verify {}: dataType={} value={}", nid, vn.getDataType(),
                                vn.getValue() != null ? vn.getValue().getValue() : "null");
                    }
                });
            }
        }

        public void updateVariableValue(String path, Object value) {
            UaVariableNode node = variableNodes.get(path);
            if (node != null) {
                Variant variant = convertToVariant(value);
                DataValue dataValue = new DataValue(variant);
                node.setValue(dataValue);
                node.fireAttributeChanged(AttributeId.Value, dataValue);
            }
        }

        private Variant convertToVariant(Object value) {
            if (value == null) return new Variant(null);
            if (value instanceof Boolean) return new Variant(value);
            if (value instanceof Short) return new Variant(value);
            if (value instanceof Integer) return new Variant(value);
            if (value instanceof Float) return new Variant(value);
            if (value instanceof Double) return new Variant(value);
            if (value instanceof String) return new Variant(value);
            return new Variant(String.valueOf(value));
        }

        private NodeId getDataTypeNodeId(Object value) {
            if (value instanceof Boolean) return Identifiers.Boolean;
            if (value instanceof Short) return Identifiers.Int16;
            if (value instanceof Integer) return Identifiers.Int32;
            if (value instanceof Float) return Identifiers.Float;
            if (value instanceof Double) return Identifiers.Double;
            if (value instanceof String) return Identifiers.String;
            return Identifiers.BaseDataType;
        }
    }

    /** 从 mock/ 目录或当前目录向上查找 mock-data.json */
    private static String findMockDataPath() {
        String[] candidates = {"mock/mock-data.json", "../mock/mock-data.json", "mock-data.json"};
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f.getAbsolutePath();
        }
        return null;
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 4840;
        MockOpcUaServer server = new MockOpcUaServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {
        }
    }
}
