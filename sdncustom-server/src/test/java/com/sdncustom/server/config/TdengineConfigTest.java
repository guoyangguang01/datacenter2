package com.sdncustom.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TdengineConfig URL 解析测试")
class TdengineConfigTest {

    @Test
    @DisplayName("解析库名")
    void parseDbName() {
        assertEquals("sdncustom", TdengineConfig.parseDbName("jdbc:TAOS://localhost:6030/sdncustom"));
        assertEquals("sdncustom", TdengineConfig.parseDbName("jdbc:TAOS-RS://localhost:6041/sdncustom"));
        assertEquals("mydb", TdengineConfig.parseDbName("jdbc:TAOS-RS://h:6041/mydb?param=1"));
        assertNull(TdengineConfig.parseDbName("jdbc:TAOS://localhost:6030"));
        assertNull(TdengineConfig.parseDbName("jdbc:TAOS://localhost:6030/"));
        assertNull(TdengineConfig.parseDbName(null));
    }

    @Test
    @DisplayName("去掉库名得到 base URL")
    void baseUrlWithoutDb() {
        assertEquals("jdbc:TAOS-RS://localhost:6041", TdengineConfig.baseUrlWithoutDb("jdbc:TAOS-RS://localhost:6041/sdncustom"));
        assertEquals("jdbc:TAOS://localhost:6030", TdengineConfig.baseUrlWithoutDb("jdbc:TAOS://localhost:6030/sdncustom"));
        assertEquals("jdbc:TAOS://localhost:6030", TdengineConfig.baseUrlWithoutDb("jdbc:TAOS://localhost:6030"));
        assertEquals("jdbc:TAOS-RS://h:6041", TdengineConfig.baseUrlWithoutDb("jdbc:TAOS-RS://h:6041/db?x=1"));
    }
}
