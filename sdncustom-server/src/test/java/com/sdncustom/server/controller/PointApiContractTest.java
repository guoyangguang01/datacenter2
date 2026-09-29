package com.sdncustom.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测点查询/详情/当前值/历史接口的 HTTP 契约。
 * PointApiIntegrationTest 覆盖编辑与导入；这里补齐查询三分支（业务/通道/方向）、
 * 分页双形态、404、value 兜底与 history 的参数校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("测点查询与历史接口 HTTP 契约 集成测试")
class PointApiContractTest {

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

    /** H2 上下文跨测试方法共享：fixture 只播种一次，重复创建会撞 400（业务已存在） */
    private static boolean seeded;

    /**
     * 业务 biz_qc 下两个通道：ch_qc_1 挂 OUTPUT 点，ch_qc_2 挂引用它的 INPUT 点。
     * 查询的三个分支（按业务 / 按通道 / 业务+通道叠加）与方向过滤都要靠这组数据。
     */
    private void seedQueryFixture(String auth) throws Exception {
        if (seeded) {
            return;
        }
        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_qc\",\"businessName\":\"查询契约业务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        for (String ch : new String[]{"ch_qc_1", "ch_qc_2"}) {
            mockMvc.perform(post("/api/channels")
                            .header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"channelId\":\"" + ch + "\",\"channelName\":\"" + ch + "\","
                                    + "\"businessId\":\"biz_qc\",\"protocolType\":\"CUSTOM_TCP\","
                                    + "\"autoConnect\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(post("/api/points")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointId\":\"p_qc_out\",\"businessId\":\"biz_qc\","
                                + "\"pointName\":\"输出点\",\"dataType\":\"FLOAT32\","
                                + "\"direction\":\"OUTPUT\",\"channelId\":\"ch_qc_1\","
                                + "\"address\":\"40001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/points")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointId\":\"p_qc_in\",\"businessId\":\"biz_qc\","
                                + "\"pointName\":\"输入点\",\"dataType\":\"FLOAT32\","
                                + "\"direction\":\"INPUT\",\"referencePointId\":\"p_qc_out\","
                                + "\"channelId\":\"ch_qc_2\",\"address\":\"write_a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        seeded = true;
    }

    @Test
    @DisplayName("查询三分支 + 方向过滤：业务 / 通道 / 业务+通道叠加 / direction")
    void findAllQueryBranchesAndDirectionFilter() throws Exception {
        String auth = bearer();
        seedQueryFixture(auth);

        // 仅业务：两个点都在
        mockMvc.perform(get("/api/points").param("businessId", "biz_qc")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 仅通道：走 findByChannelId 分支
        mockMvc.perform(get("/api/points").param("channelId", "ch_qc_2")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pointId").value("p_qc_in"));

        // 业务 + 通道：内存过滤叠加
        mockMvc.perform(get("/api/points")
                        .param("businessId", "biz_qc").param("channelId", "ch_qc_1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pointId").value("p_qc_out"));

        // 方向过滤
        mockMvc.perform(get("/api/points")
                        .param("businessId", "biz_qc").param("direction", "INPUT")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].pointId").value("p_qc_in"));

        // 业务 + 通道 + 方向：交集为空
        mockMvc.perform(get("/api/points")
                        .param("businessId", "biz_qc").param("channelId", "ch_qc_1")
                        .param("direction", "INPUT")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("direction 参数值非法：400 参数类型不合法而不是 500")
    void invalidDirectionParamIs400() throws Exception {
        String auth = bearer();

        mockMvc.perform(get("/api/points").param("direction", "SIDEWAYS")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("direction=SIDEWAYS")));
    }

    @Test
    @DisplayName("分页双形态：不传 size 是全量数组，传 size 是 {items,total,page,size}")
    void findAllPagingShapes() throws Exception {
        String auth = bearer();
        seedQueryFixture(auth);

        mockMvc.perform(get("/api/points").param("businessId", "biz_qc")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/points").param("businessId", "biz_qc")
                        .param("page", "0").param("size", "1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1));
    }

    @Test
    @DisplayName("查详情：存在返回点，不存在信封 404")
    void findByIdReturnsPointOr404() throws Exception {
        String auth = bearer();
        seedQueryFixture(auth);

        mockMvc.perform(get("/api/points/p_qc_out").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pointId").value("p_qc_out"));

        mockMvc.perform(get("/api/points/p_no_such").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message", containsString("p_no_such")));
    }

    @Test
    @DisplayName("创建缺 address：@Valid 拦成 400 并点名字段")
    void createRequiresAddress() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/points")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointId\":\"p_noaddr\",\"businessId\":\"biz_qc\","
                                + "\"pointName\":\"没地址\",\"dataType\":\"INT16\","
                                + "\"direction\":\"OUTPUT\",\"channelId\":\"ch_qc_1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("address")));
    }

    @Test
    @DisplayName("删除不存在的测点：信封 404")
    void deleteMissingPointIs404() throws Exception {
        String auth = bearer();

        mockMvc.perform(delete("/api/points/p_never_it").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("当前值：缓存未命中时兜底 COMM_LOST（不是 404/500）")
    void getValueFallsBackToCommLost() throws Exception {
        String auth = bearer();
        seedQueryFixture(auth);

        mockMvc.perform(get("/api/points/p_qc_out/value").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pointId").value("p_qc_out"))
                .andExpect(jsonPath("$.data.quality").value("COMM_LOST"));
    }

    @Test
    @DisplayName("历史查询参数校验：时间倒挂 / 负页码 / size 越界各回 400 点名原因")
    void historyParameterValidation() throws Exception {
        String auth = bearer();
        seedQueryFixture(auth);

        mockMvc.perform(get("/api/points/p_qc_out/history")
                        .param("startTime", "2000").param("endTime", "1000")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("startTime 不能晚于 endTime")));

        mockMvc.perform(get("/api/points/p_qc_out/history")
                        .param("startTime", "0").param("endTime", "1")
                        .param("page", "-1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("page 不能为负")));

        mockMvc.perform(get("/api/points/p_qc_out/history")
                        .param("startTime", "0").param("endTime", "1")
                        .param("size", "0")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("size 必须在 1..")));

        mockMvc.perform(get("/api/points/p_qc_out/history")
                        .param("startTime", "0").param("endTime", "1")
                        .param("size", "1001")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("历史查询合法参数：200 且返回数组（无历史数据时为空数组）")
    void historyHappyPathReturnsList() throws Exception {
        String auth = bearer();
        seedQueryFixture(auth);

        mockMvc.perform(get("/api/points/p_qc_out/history")
                        .param("startTime", "0")
                        .param("endTime", String.valueOf(System.currentTimeMillis()))
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
