package com.sdncustom.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据导入导出接口的 HTTP 契约测试。
 *
 * <p>controller 直接解析裸 Map（不走 DTO 的 bean validation），
 * 这些"必须是数组/必须是对象/缺字段"的分支只有真实 HTTP 路径能覆盖到。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("数据导入导出接口 HTTP 契约 集成测试")
class DataTransferApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String bearer() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testadmin\",\"password\":\"testpass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return "Bearer " + body.get("data").get("token").asText();
    }

    // ---------- 导出 ----------

    @Test
    @DisplayName("导出是裸 payload（无 ApiResponse 信封），可原样回灌")
    void exportReturnsBarePayloadWithoutEnvelope() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_exp\",\"businessName\":\"导出业务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_exp\",\"channelName\":\"导出通道\","
                                + "\"businessId\":\"biz_exp\",\"protocolType\":\"CUSTOM_TCP\","
                                + "\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/points")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointId\":\"p_exp\",\"businessId\":\"biz_exp\","
                                + "\"pointName\":\"导出测点\",\"dataType\":\"INT16\","
                                + "\"direction\":\"OUTPUT\",\"channelId\":\"ch_exp\",\"address\":\"40001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 顶层直接是 businesses / points 数组——没有 code/data 包装，文件才能直接再导入
        mockMvc.perform(get("/api/data/export").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.businesses").isArray())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.businesses[?(@.businessId=='biz_exp')]").isNotEmpty())
                .andExpect(jsonPath("$.points[?(@.pointId=='p_exp')]").isNotEmpty());
    }

    // ---------- JSON 导入 ----------

    @Test
    @DisplayName("JSON 导入：businesses + channels + points 一次建齐，计数正确")
    void importCreatesBusinessesChannelsAndPoints() throws Exception {
        String auth = bearer();

        String payload = "{\"businesses\":["
                + "{\"businessId\":\"biz_imp\",\"businessName\":\"导入业务\"}],"
                + "\"channels\":[{\"channelId\":\"ch_imp\",\"businessId\":\"biz_imp\","
                + "\"channelName\":\"导入通道\",\"protocolType\":\"CUSTOM_TCP\",\"autoConnect\":false}],"
                + "\"points\":[{\"pointId\":\"p_imp\",\"businessId\":\"biz_imp\","
                + "\"pointName\":\"导入测点\",\"dataType\":\"FLOAT32\",\"direction\":\"OUTPUT\","
                + "\"channelId\":\"ch_imp\",\"address\":\"40001\",\"deadband\":0.5}]}";

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.businessCount").value(1))
                .andExpect(jsonPath("$.data.channelCount").value(1))
                .andExpect(jsonPath("$.data.pointCount").value(1));

        // 导入的数据能通过常规查询读到
        mockMvc.perform(get("/api/points").param("channelId", "ch_imp")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.pointId=='p_imp')]").isNotEmpty());

        // 也能从导出里读回（导入 → 导出 回环）
        mockMvc.perform(get("/api/data/export").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[?(@.pointId=='p_imp')]").isNotEmpty());
    }

    @Test
    @DisplayName("导入空 payload（无 businesses 也无 points）：400 并说明缺什么")
    void importRejectsPayloadWithoutPointsOrBusinesses() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("缺少 'points'")));
    }

    @Test
    @DisplayName("导入 businesses 非数组：400 点名字段")
    void importRejectsNonArrayBusinesses() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businesses\":\"oops\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("'businesses' 必须是数组")));
    }

    @Test
    @DisplayName("导入 points 非数组：400 点名字段")
    void importRejectsNonArrayPoints() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("'points' 必须是数组")));
    }

    @Test
    @DisplayName("导入 channels 非数组：400 点名字段（channels 段先于 points 解析）")
    void importRejectsNonArrayChannels() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channels\":{\"id\":\"x\"},\"points\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("'channels' 必须是数组")));
    }

    @Test
    @DisplayName("导入业务项不是对象：400 点名'业务项必须是对象'")
    void importRejectsNonObjectBusinessItem() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businesses\":[\"oops\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("业务项必须是对象")));
    }

    @Test
    @DisplayName("导入业务缺 businessId：400 点名缺失字段")
    void importRejectsBusinessMissingId() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businesses\":[{\"businessName\":\"没 id\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("缺少必填字段: businessId")));
    }

    // ---------- CSV 导入 ----------

    @Test
    @DisplayName("CSV 导入空文件：400 提示上传文件")
    void importCsvRejectsEmptyFile() throws Exception {
        String auth = bearer();

        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.csv", "text/csv", new byte[0]);

        mockMvc.perform(multipart("/api/data/import-csv")
                        .file(empty)
                        .param("businessId", "biz_csv_empty")
                        .param("channelId", "ch_csv_empty")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("请上传 CSV 文件")));
    }

    @Test
    @DisplayName("CSV 导入：业务/通道自动创建，测点入库并可查询")
    void importCsvCreatesBusinessChannelAndPoint() throws Exception {
        String auth = bearer();

        String csv = "pointId,pointName,address,dataType,unit,direction,referencePointId,deadband\n"
                + "csv_p1,CSV测点A,40001,INT16,C,OUTPUT,,0\n"
                + "csv_p2,CSV测点B,40002,FLOAT32,%,OUTPUT,,0.1\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "points.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/data/import-csv")
                        .file(file)
                        .param("businessId", "biz_csv_imp")
                        .param("channelId", "ch_csv_imp")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // CSV 导入不处理业务段：businessCount 恒 0
                .andExpect(jsonPath("$.data.businessCount").value(0))
                .andExpect(jsonPath("$.data.channelCount").value(1))
                .andExpect(jsonPath("$.data.pointCount").value(2));

        mockMvc.perform(get("/api/points").param("channelId", "ch_csv_imp")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.pointId=='csv_p1')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.pointId=='csv_p2')]").isNotEmpty());
    }

    @Test
    @DisplayName("CSV 缺必填列（direction）：报错点名行号与列名，整批拒绝")
    void importCsvMissingRequiredColumnReportsRowAndField() throws Exception {
        String auth = bearer();

        String csv = "pointId,pointName,address,dataType,unit\n"
                + "csv_bad,缺方向,40003,INT16,C\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/data/import-csv")
                        .file(file)
                        .param("businessId", "biz_csv_bad")
                        .param("channelId", "ch_csv_bad")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("第2行")))
                .andExpect(jsonPath("$.message", containsString("direction")));
    }
}
