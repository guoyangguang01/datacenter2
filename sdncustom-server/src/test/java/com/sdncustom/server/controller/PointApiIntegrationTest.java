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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 路径的测点接口契约测试。
 *
 * <p>存在的理由：下面这些行为只在「controller 的 {@code @Valid} + service 的静默忽略」一起作用下
 * 才成立，{@code DTOTest} 的纯 bean validation 断言看不见，service 单测又不带 {@code @Valid}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("测点接口 HTTP 契约 集成测试")
class PointApiIntegrationTest {

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

    /**
     * C1：编辑测点的请求体不带 direction（前端刻意不传，方向创建后不可变更），必须能存下去。
     * DTO 上的 {@code @NotNull} 会让 {@code @Valid} 在 service 之前把请求拦成 400——
     * 名称、单位、死区就全都改不了。
     */
    @Test
    @DisplayName("PUT 测点不传 direction：编辑成功（方向不可变更，缺失时静默忽略）")
    void updatePointWithoutDirectionSucceeds() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_c1\",\"businessName\":\"C1 业务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_c1\",\"channelName\":\"C1 通道\",\"businessId\":\"biz_c1\","
                                + "\"protocolType\":\"CUSTOM_TCP\",\"direction\":\"READ_WRITE\","
                                + "\"connectionConfig\":\"{\\\"host\\\":\\\"127.0.0.1\\\",\\\"port\\\":9002}\","
                                + "\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/points")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointId\":\"p_c1\",\"businessId\":\"biz_c1\",\"pointName\":\"改名前\","
                                + "\"dataType\":\"FLOAT32\",\"direction\":\"OUTPUT\","
                                + "\"channelId\":\"ch_c1\",\"address\":\"40001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 编辑：与前端 PointPage 构造的 payload 一致——不带 direction / referencePointId
        mockMvc.perform(put("/api/points/p_c1")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pointId\":\"p_c1\",\"pointName\":\"改名后\",\"dataType\":\"FLOAT32\","
                                + "\"unit\":\"C\",\"deadband\":0.5,"
                                + "\"channelId\":\"ch_c1\",\"address\":\"40001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.pointName").value("改名后"))
                .andExpect(jsonPath("$.data.unit").value("C"))
                // 方向未被编辑请求清空：DTO 里没有它 = 保持原值
                .andExpect(jsonPath("$.data.direction").value("OUTPUT"));
    }

    /**
     * I6：导入文件里有多条方向不合格的测点时，报错必须一次点名每一条，而不是在第一条上抛
     * 「缺少必填字段: direction」——既点不出是哪条记录，也看不到文件里其它的问题。
     * 走的是真实 HTTP 路径：controller 解析裸 Map，DTO 的 bean validation 在这条路上不生效。
     */
    @Test
    @DisplayName("导入：方向不合格的测点一次报全并逐条点名")
    void importReportsEveryBadDirectionInOneMessage() throws Exception {
        String auth = bearer();

        String payload = "{\"points\":["
                + "{\"pointId\":\"no_dir\",\"businessId\":\"biz_x\",\"pointName\":\"无方向\","
                + "\"dataType\":\"FLOAT32\",\"channelId\":\"ch_x\",\"address\":\"a\"},"
                + "{\"pointId\":\"bad_dir\",\"businessId\":\"biz_x\",\"pointName\":\"方向非法\","
                + "\"dataType\":\"FLOAT32\",\"direction\":\"SIDEWAYS\","
                + "\"channelId\":\"ch_x\",\"address\":\"b\"}"
                + "]}";

        mockMvc.perform(post("/api/data/import")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                // 两条都必须被点名，且各自带上原因
                .andExpect(jsonPath("$.message").value(containsString("no_dir")))
                .andExpect(jsonPath("$.message").value(containsString("bad_dir")))
                .andExpect(jsonPath("$.message").value(containsString("SIDEWAYS")));
    }

}
