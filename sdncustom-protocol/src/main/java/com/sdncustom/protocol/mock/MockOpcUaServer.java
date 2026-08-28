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
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNodeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.*;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;

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
     * 初始化模拟数据
     */
    private void initMockData() {
        // 温度节点
        nodeValues.put("Temperature/Room1", 25.6);
        nodeValues.put("Temperature/Room2", 23.4);
        nodeValues.put("Temperature/Outdoor", 30.1);
        nodeValues.put("Temperature/Warehouse", 18.5);
        nodeValues.put("Temperature/ColdRoom", -5.2);
        nodeValues.put("Temperature/Boiler", 85.3);
        nodeValues.put("Temperature/Chiller", 7.8);
        nodeValues.put("Temperature/Ambient", 28.4);
        nodeValues.put("Temperature/Inlet", 45.6);
        nodeValues.put("Temperature/Outlet", 67.8);

        // 压力节点
        nodeValues.put("Pressure/Pipeline1", 101.3);
        nodeValues.put("Pressure/Pipeline2", 202.6);
        nodeValues.put("Pressure/Steam", 350.5);
        nodeValues.put("Pressure/Hydraulic", 1500.0);
        nodeValues.put("Pressure/Pneumatic", 600.0);
        nodeValues.put("Pressure/Vacuum", -95.0);
        nodeValues.put("Pressure/Tank1", 150.2);
        nodeValues.put("Pressure/Tank2", 180.8);

        // 流量节点
        nodeValues.put("Flow/MainPipe", 50.0);
        nodeValues.put("Flow/Branch1", 20.0);
        nodeValues.put("Flow/Branch2", 15.0);
        nodeValues.put("Flow/Return", 45.0);
        nodeValues.put("Flow/Cooling", 30.0);
        nodeValues.put("Flow/Heating", 25.0);

        // 液位节点
        nodeValues.put("Level/Tank1", 75.5);
        nodeValues.put("Level/Tank2", 45.2);
        nodeValues.put("Level/Tank3", 88.9);
        nodeValues.put("Level/Sump", 32.1);
        nodeValues.put("Level/Buffer", 60.0);
        nodeValues.put("Level/Storage", 42.5);

        // 设备状态
        nodeValues.put("Status/Pump01", true);
        nodeValues.put("Status/Pump02", false);
        nodeValues.put("Status/Pump03", true);
        nodeValues.put("Status/Valve01", true);
        nodeValues.put("Status/Valve02", false);
        nodeValues.put("Status/Valve03", true);
        nodeValues.put("Status/Fan01", true);
        nodeValues.put("Status/Fan02", false);
        nodeValues.put("Status/Heater01", false);
        nodeValues.put("Status/Heater02", true);
        nodeValues.put("Status/Motor01", true);
        nodeValues.put("Status/Motor02", false);

        // 计数器
        nodeValues.put("Counter/Production", 12345);
        nodeValues.put("Counter/Defects", 23);
        nodeValues.put("Counter/ProductA", 8000);
        nodeValues.put("Counter/ProductB", 4345);
        nodeValues.put("Counter/Rework", 156);
        nodeValues.put("Counter/Scrap", 45);
        nodeValues.put("Counter/AlarmCount", 12);
        nodeValues.put("Counter/MaintenanceCount", 5);

        // 电表数据
        nodeValues.put("Power/Voltage", 220.5);
        nodeValues.put("Power/Current", 5.2);
        nodeValues.put("Power/ActivePower", 1145.1);
        nodeValues.put("Power/PowerFactor", 0.85);
        nodeValues.put("Power/Frequency", 50.01);
        nodeValues.put("Power/Energy", 12345.6);

        // 环境监测
        nodeValues.put("Environment/CO2", 450.0);
        nodeValues.put("Environment/PM25", 35.0);
        nodeValues.put("Environment/Noise", 65.0);
        nodeValues.put("Environment/Light", 500.0);

        // 字符串节点
        nodeValues.put("Alarm/Status", "Normal");
        nodeValues.put("Alarm/Critical", "None");
        nodeValues.put("Alarm/Warning", "None");
        nodeValues.put("Mode/Operation", "Auto");
        nodeValues.put("Mode/Production", "Normal");
        nodeValues.put("Status/SystemStatus", "Running");
        nodeValues.put("Status/Communication", "Online");
        nodeValues.put("Status/Quality", "OK");
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
     * 更新模拟数据
     */
    private void updateMockData() {
        if (!running || namespace == null) return;

        try {
            // 温度波动
            updateNodeValue("Temperature/Room1", 25.6 + (random.nextDouble() - 0.5) * 4);
            updateNodeValue("Temperature/Room2", 23.4 + (random.nextDouble() - 0.5) * 3);
            updateNodeValue("Temperature/Outdoor", 30.1 + (random.nextDouble() - 0.5) * 6);
            updateNodeValue("Temperature/Warehouse", 18.5 + (random.nextDouble() - 0.5) * 4);
            updateNodeValue("Temperature/ColdRoom", -5.2 + (random.nextDouble() - 0.5) * 3);
            updateNodeValue("Temperature/Boiler", 85.3 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Temperature/Chiller", 7.8 + (random.nextDouble() - 0.5) * 4);
            updateNodeValue("Temperature/Ambient", 28.4 + (random.nextDouble() - 0.5) * 5);
            updateNodeValue("Temperature/Inlet", 45.6 + (random.nextDouble() - 0.5) * 6);
            updateNodeValue("Temperature/Outlet", 67.8 + (random.nextDouble() - 0.5) * 8);

            // 压力波动
            updateNodeValue("Pressure/Pipeline1", 101.3 + (random.nextDouble() - 0.5) * 5);
            updateNodeValue("Pressure/Pipeline2", 202.6 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Pressure/Steam", 350.5 + (random.nextDouble() - 0.5) * 20);
            updateNodeValue("Pressure/Hydraulic", 1500.0 + (random.nextDouble() - 0.5) * 100);
            updateNodeValue("Pressure/Pneumatic", 600.0 + (random.nextDouble() - 0.5) * 30);
            updateNodeValue("Pressure/Vacuum", -95.0 + (random.nextDouble() - 0.5) * 5);
            updateNodeValue("Pressure/Tank1", 150.2 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Pressure/Tank2", 180.8 + (random.nextDouble() - 0.5) * 12);

            // 流量波动
            updateNodeValue("Flow/MainPipe", 50.0 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Flow/Branch1", 20.0 + (random.nextDouble() - 0.5) * 5);
            updateNodeValue("Flow/Branch2", 15.0 + (random.nextDouble() - 0.5) * 4);
            updateNodeValue("Flow/Return", 45.0 + (random.nextDouble() - 0.5) * 8);
            updateNodeValue("Flow/Cooling", 30.0 + (random.nextDouble() - 0.5) * 6);
            updateNodeValue("Flow/Heating", 25.0 + (random.nextDouble() - 0.5) * 5);

            // 液位波动
            updateNodeValue("Level/Tank1", 75.5 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Level/Tank2", 45.2 + (random.nextDouble() - 0.5) * 8);
            updateNodeValue("Level/Tank3", 88.9 + (random.nextDouble() - 0.5) * 6);
            updateNodeValue("Level/Sump", 32.1 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Level/Buffer", 60.0 + (random.nextDouble() - 0.5) * 8);
            updateNodeValue("Level/Storage", 42.5 + (random.nextDouble() - 0.5) * 6);

            // 设备状态
            updateNodeValue("Status/Pump01", random.nextDouble() > 0.1);
            updateNodeValue("Status/Pump02", random.nextDouble() > 0.7);
            updateNodeValue("Status/Pump03", random.nextDouble() > 0.2);
            updateNodeValue("Status/Valve01", random.nextBoolean());
            updateNodeValue("Status/Valve02", random.nextBoolean());
            updateNodeValue("Status/Valve03", random.nextBoolean());
            updateNodeValue("Status/Fan01", random.nextDouble() > 0.15);
            updateNodeValue("Status/Fan02", random.nextDouble() > 0.6);
            updateNodeValue("Status/Heater01", random.nextDouble() > 0.8);
            updateNodeValue("Status/Heater02", random.nextDouble() > 0.3);
            updateNodeValue("Status/Motor01", random.nextDouble() > 0.1);
            updateNodeValue("Status/Motor02", random.nextDouble() > 0.5);

            // 计数器
            updateNodeValue("Counter/Production", (int) nodeValues.get("Counter/Production") + random.nextInt(5));
            if (random.nextDouble() < 0.1) {
                updateNodeValue("Counter/Defects", (int) nodeValues.get("Counter/Defects") + 1);
            }
            updateNodeValue("Counter/ProductA", (int) nodeValues.get("Counter/ProductA") + random.nextInt(3));
            updateNodeValue("Counter/ProductB", (int) nodeValues.get("Counter/ProductB") + random.nextInt(2));
            if (random.nextDouble() < 0.05) {
                updateNodeValue("Counter/Rework", (int) nodeValues.get("Counter/Rework") + 1);
            }
            if (random.nextDouble() < 0.02) {
                updateNodeValue("Counter/Scrap", (int) nodeValues.get("Counter/Scrap") + 1);
            }

            // 电表数据
            updateNodeValue("Power/Voltage", 220.0 + (random.nextDouble() - 0.5) * 10);
            updateNodeValue("Power/Current", 5.0 + (random.nextDouble() - 0.5) * 2);
            updateNodeValue("Power/ActivePower", (double) nodeValues.get("Power/Voltage") * (double) nodeValues.get("Power/Current"));
            updateNodeValue("Power/PowerFactor", 0.85 + (random.nextDouble() - 0.5) * 0.1);
            updateNodeValue("Power/Frequency", 50.0 + (random.nextDouble() - 0.5) * 0.1);
            updateNodeValue("Power/Energy", (double) nodeValues.get("Power/Energy") + random.nextDouble() * 5);

            // 环境监测
            updateNodeValue("Environment/CO2", 450.0 + (random.nextDouble() - 0.5) * 100);
            updateNodeValue("Environment/PM25", 35.0 + (random.nextDouble() - 0.5) * 20);
            updateNodeValue("Environment/Noise", 65.0 + (random.nextDouble() - 0.5) * 15);
            updateNodeValue("Environment/Light", 500.0 + (random.nextDouble() - 0.5) * 200);

            // 告警
            String[] alarms = {"Normal", "High Temperature", "Low Pressure", "Overload", "Sensor Fault"};
            updateNodeValue("Alarm/Status", random.nextDouble() < 0.8 ? "Normal" : alarms[random.nextInt(alarms.length)]);
            updateNodeValue("Alarm/Critical", random.nextDouble() < 0.95 ? "None" : "Critical: " + alarms[random.nextInt(alarms.length)]);
            updateNodeValue("Alarm/Warning", random.nextDouble() < 0.85 ? "None" : "Warning: " + alarms[random.nextInt(alarms.length)]);

            // 模式
            String[] modes = {"Auto", "Manual", "Maintenance", "Setup"};
            updateNodeValue("Mode/Operation", modes[random.nextInt(modes.length)]);
            String[] prodModes = {"Normal", "HighSpeed", "Eco", "Test"};
            updateNodeValue("Mode/Production", prodModes[random.nextInt(prodModes.length)]);

            // 状态
            String[] sysStatus = {"Running", "Idle", "Maintenance", "Error"};
            updateNodeValue("Status/SystemStatus", random.nextDouble() < 0.9 ? "Running" : sysStatus[random.nextInt(sysStatus.length)]);
            String[] commStatus = {"Online", "Offline", "Timeout", "Error"};
            updateNodeValue("Status/Communication", random.nextDouble() < 0.9 ? "Online" : commStatus[random.nextInt(commStatus.length)]);
            String[] quality = {"OK", "NG", "Pending", "Rework"};
            updateNodeValue("Status/Quality", random.nextDouble() < 0.9 ? "OK" : quality[random.nextInt(quality.length)]);

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

        public MockNamespace(OpcUaServer server, String namespaceUri) {
            super(server, namespaceUri);
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
            // 订阅创建时的回调
        }

        @Override
        public void onDataItemsModified(List<DataItem> dataItems) {
            // 订阅修改时的回调
        }

        @Override
        public void onDataItemsDeleted(List<DataItem> dataItems) {
            // 订阅删除时的回调
        }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
            // 监控模式变更时的回调
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
         * 创建所有节点
         */
        private void createNodes() {
            UaNodeContext context = getNodeContext();
            UaNodeManager nodeManager = getNodeManager();

            // 创建根文件夹
            UaFolderNode rootFolder = createFolder(context, "SDNCustom", "SDNCustom Mock Data");

            // 温度文件夹
            UaFolderNode tempFolder = createFolder(context, "Temperature", "Temperature Sensors");
            rootFolder.addOrganizes(tempFolder);
            createVariableNode(context, tempFolder, "Room1", "Room 1 Temperature", 25.6);
            createVariableNode(context, tempFolder, "Room2", "Room 2 Temperature", 23.4);
            createVariableNode(context, tempFolder, "Outdoor", "Outdoor Temperature", 30.1);
            createVariableNode(context, tempFolder, "Warehouse", "Warehouse Temperature", 18.5);
            createVariableNode(context, tempFolder, "ColdRoom", "Cold Room Temperature", -5.2);
            createVariableNode(context, tempFolder, "Boiler", "Boiler Temperature", 85.3);
            createVariableNode(context, tempFolder, "Chiller", "Chiller Temperature", 7.8);
            createVariableNode(context, tempFolder, "Ambient", "Ambient Temperature", 28.4);
            createVariableNode(context, tempFolder, "Inlet", "Inlet Temperature", 45.6);
            createVariableNode(context, tempFolder, "Outlet", "Outlet Temperature", 67.8);

            // 压力文件夹
            UaFolderNode pressureFolder = createFolder(context, "Pressure", "Pressure Sensors");
            rootFolder.addOrganizes(pressureFolder);
            createVariableNode(context, pressureFolder, "Pipeline1", "Pipeline 1 Pressure", 101.3);
            createVariableNode(context, pressureFolder, "Pipeline2", "Pipeline 2 Pressure", 202.6);
            createVariableNode(context, pressureFolder, "Steam", "Steam Pressure", 350.5);
            createVariableNode(context, pressureFolder, "Hydraulic", "Hydraulic Pressure", 1500.0);
            createVariableNode(context, pressureFolder, "Pneumatic", "Pneumatic Pressure", 600.0);
            createVariableNode(context, pressureFolder, "Vacuum", "Vacuum Pressure", -95.0);
            createVariableNode(context, pressureFolder, "Tank1", "Tank 1 Pressure", 150.2);
            createVariableNode(context, pressureFolder, "Tank2", "Tank 2 Pressure", 180.8);

            // 流量文件夹
            UaFolderNode flowFolder = createFolder(context, "Flow", "Flow Sensors");
            rootFolder.addOrganizes(flowFolder);
            createVariableNode(context, flowFolder, "MainPipe", "Main Pipe Flow", 50.0);
            createVariableNode(context, flowFolder, "Branch1", "Branch 1 Flow", 20.0);
            createVariableNode(context, flowFolder, "Branch2", "Branch 2 Flow", 15.0);
            createVariableNode(context, flowFolder, "Return", "Return Flow", 45.0);
            createVariableNode(context, flowFolder, "Cooling", "Cooling Flow", 30.0);
            createVariableNode(context, flowFolder, "Heating", "Heating Flow", 25.0);

            // 液位文件夹
            UaFolderNode levelFolder = createFolder(context, "Level", "Level Sensors");
            rootFolder.addOrganizes(levelFolder);
            createVariableNode(context, levelFolder, "Tank1", "Tank 1 Level", 75.5);
            createVariableNode(context, levelFolder, "Tank2", "Tank 2 Level", 45.2);
            createVariableNode(context, levelFolder, "Tank3", "Tank 3 Level", 88.9);
            createVariableNode(context, levelFolder, "Sump", "Sump Level", 32.1);
            createVariableNode(context, levelFolder, "Buffer", "Buffer Level", 60.0);
            createVariableNode(context, levelFolder, "Storage", "Storage Level", 42.5);

            // 设备状态文件夹
            UaFolderNode statusFolder = createFolder(context, "Status", "Device Status");
            rootFolder.addOrganizes(statusFolder);
            createVariableNode(context, statusFolder, "Pump01", "Pump 01 Status", true);
            createVariableNode(context, statusFolder, "Pump02", "Pump 02 Status", false);
            createVariableNode(context, statusFolder, "Pump03", "Pump 03 Status", true);
            createVariableNode(context, statusFolder, "Valve01", "Valve 01 Status", true);
            createVariableNode(context, statusFolder, "Valve02", "Valve 02 Status", false);
            createVariableNode(context, statusFolder, "Valve03", "Valve 03 Status", true);
            createVariableNode(context, statusFolder, "Fan01", "Fan 01 Status", true);
            createVariableNode(context, statusFolder, "Fan02", "Fan 02 Status", false);
            createVariableNode(context, statusFolder, "Heater01", "Heater 01 Status", false);
            createVariableNode(context, statusFolder, "Heater02", "Heater 02 Status", true);
            createVariableNode(context, statusFolder, "Motor01", "Motor 01 Status", true);
            createVariableNode(context, statusFolder, "Motor02", "Motor 02 Status", false);

            // 计数器文件夹
            UaFolderNode counterFolder = createFolder(context, "Counter", "Counters");
            rootFolder.addOrganizes(counterFolder);
            createVariableNode(context, counterFolder, "Production", "Production Counter", 12345);
            createVariableNode(context, counterFolder, "Defects", "Defects Counter", 23);
            createVariableNode(context, counterFolder, "ProductA", "Product A Counter", 8000);
            createVariableNode(context, counterFolder, "ProductB", "Product B Counter", 4345);
            createVariableNode(context, counterFolder, "Rework", "Rework Counter", 156);
            createVariableNode(context, counterFolder, "Scrap", "Scrap Counter", 45);
            createVariableNode(context, counterFolder, "AlarmCount", "Alarm Count", 12);
            createVariableNode(context, counterFolder, "MaintenanceCount", "Maintenance Count", 5);

            // 电表文件夹
            UaFolderNode powerFolder = createFolder(context, "Power", "Power Monitoring");
            rootFolder.addOrganizes(powerFolder);
            createVariableNode(context, powerFolder, "Voltage", "Voltage", 220.5);
            createVariableNode(context, powerFolder, "Current", "Current", 5.2);
            createVariableNode(context, powerFolder, "ActivePower", "Active Power", 1145.1);
            createVariableNode(context, powerFolder, "PowerFactor", "Power Factor", 0.85);
            createVariableNode(context, powerFolder, "Frequency", "Frequency", 50.01);
            createVariableNode(context, powerFolder, "Energy", "Energy", 12345.6);

            // 环境文件夹
            UaFolderNode envFolder = createFolder(context, "Environment", "Environment Monitoring");
            rootFolder.addOrganizes(envFolder);
            createVariableNode(context, envFolder, "CO2", "CO2 Concentration", 450.0);
            createVariableNode(context, envFolder, "PM25", "PM2.5", 35.0);
            createVariableNode(context, envFolder, "Noise", "Noise Level", 65.0);
            createVariableNode(context, envFolder, "Light", "Light Intensity", 500.0);

            // 告警文件夹
            UaFolderNode alarmFolder = createFolder(context, "Alarm", "Alarms");
            rootFolder.addOrganizes(alarmFolder);
            createVariableNode(context, alarmFolder, "Status", "Alarm Status", "Normal");
            createVariableNode(context, alarmFolder, "Critical", "Critical Alarm", "None");
            createVariableNode(context, alarmFolder, "Warning", "Warning Alarm", "None");

            // 模式文件夹
            UaFolderNode modeFolder = createFolder(context, "Mode", "Operation Modes");
            rootFolder.addOrganizes(modeFolder);
            createVariableNode(context, modeFolder, "Operation", "Operation Mode", "Auto");
            createVariableNode(context, modeFolder, "Production", "Production Mode", "Normal");

            // 系统状态文件夹
            UaFolderNode sysFolder = createFolder(context, "System", "System Status");
            rootFolder.addOrganizes(sysFolder);
            createVariableNode(context, sysFolder, "SystemStatus", "System Status", "Running");
            createVariableNode(context, sysFolder, "Communication", "Communication Status", "Online");
            createVariableNode(context, sysFolder, "Quality", "Quality Status", "OK");

            // 添加根文件夹到 Objects 文件夹
            nodeManager.addNode(rootFolder);
            nodeManager.addReference(new Reference(
                    Identifiers.ObjectsFolder,
                    Identifiers.Organizes,
                    rootFolder.getNodeId().expanded(),
                    true
            ));

            log.info("Created {} mock nodes in OPC-UA server", variableNodes.size());
        }

        private UaFolderNode createFolder(UaNodeContext context, String name, String description) {
            NodeId nodeId = new NodeId(getNamespaceIndex(), name);
            return new UaFolderNode(
                    context,
                    nodeId,
                    new org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName(getNamespaceIndex(), name),
                    LocalizedText.english(name)
            );
        }

        private void createVariableNode(UaNodeContext context, UaFolderNode parent, String name, String description, Object value) {
            String path = parent.getNodeId().getIdentifier().toString() + "/" + name;
            NodeId nodeId = new NodeId(getNamespaceIndex(), path);
            Variant variant = convertToVariant(value);

            UaVariableNode variableNode = new UaVariableNode(
                    context,
                    nodeId,
                    new org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName(getNamespaceIndex(), name),
                    LocalizedText.english(name)
            );
            variableNode.setValue(new DataValue(variant));
            variableNode.setDataType(getDataTypeNodeId(value));
            variableNode.setAccessLevel(UByte.valueOf(3)); // READ_WRITE
            variableNode.setUserAccessLevel(UByte.valueOf(3)); // READ_WRITE

            getNodeManager().addNode(variableNode);
            parent.addOrganizes(variableNode);
            variableNodes.put(path, variableNode);
        }

        public void updateVariableValue(String path, Object value) {
            UaVariableNode node = variableNodes.get(path);
            if (node != null) {
                Variant variant = convertToVariant(value);
                node.setValue(new DataValue(variant));
            }
        }

        private Variant convertToVariant(Object value) {
            if (value == null) return new Variant(null);
            if (value instanceof Boolean) return new Variant(value);
            if (value instanceof Integer) return new Variant(value);
            if (value instanceof Double) return new Variant(value);
            if (value instanceof String) return new Variant(value);
            return new Variant(String.valueOf(value));
        }

        private NodeId getDataTypeNodeId(Object value) {
            if (value instanceof Boolean) return Identifiers.Boolean;
            if (value instanceof Integer) return Identifiers.Int32;
            if (value instanceof Double) return Identifiers.Double;
            if (value instanceof String) return Identifiers.String;
            return Identifiers.BaseDataType;
        }
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
