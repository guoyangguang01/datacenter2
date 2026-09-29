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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通道接口的 HTTP 契约测试：@Valid 校验、全量/分页双形态、按业务过滤、
 * 删除与 404 语义——这些只在真实请求路径上才成立。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("通道接口 HTTP 契约 集成测试")
class ChannelApiIntegrationTest {

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

    private void createBusiness(String auth, String businessId) throws Exception {
        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"" + businessId + "\",\"businessName\":\""
                                + businessId + " 业务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void createChannel(String auth, String channelId, String businessId, String name) throws Exception {
        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + channelId + "\",\"channelName\":\"" + name + "\","
                                + "\"businessId\":\"" + businessId + "\","
                                + "\"protocolType\":\"CUSTOM_TCP\",\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ---------- 创建校验（@Valid 只在 HTTP 路径生效） ----------

    @Test
    @DisplayName("创建缺 channelId：400 并点名字段")
    void createRequiresChannelId() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_vc");

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelName\":\"没有 id\",\"businessId\":\"biz_vc\","
                                + "\"protocolType\":\"CUSTOM_TCP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("channelId")));
    }

    @Test
    @DisplayName("创建缺 protocolType：400 并点名字段")
    void createRequiresProtocolType() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_vp");

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_vp\",\"channelName\":\"没有协议\","
                                + "\"businessId\":\"biz_vp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("protocolType")));
    }

    @Test
    @DisplayName("创建指定不存在的业务：400 点名业务")
    void createRejectsUnknownBusiness() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_nobiz\",\"channelName\":\"孤儿通道\","
                                + "\"businessId\":\"biz_does_not_exist\","
                                + "\"protocolType\":\"CUSTOM_TCP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("业务不存在: biz_does_not_exist")));
    }

    @Test
    @DisplayName("同一业务下 code 重复：400 点名编码")
    void createRejectsDuplicateCodeInBusiness() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_dcode");

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_dcode_1\",\"channelName\":\"先到\","
                                + "\"businessId\":\"biz_dcode\",\"code\":\"FZXT\","
                                + "\"protocolType\":\"CUSTOM_TCP\",\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_dcode_2\",\"channelName\":\"后到\","
                                + "\"businessId\":\"biz_dcode\",\"code\":\"FZXT\","
                                + "\"protocolType\":\"CUSTOM_TCP\",\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("该业务下通道编码已存在: FZXT")));
    }

    // ---------- 查询：全量 / 分页双形态 ----------

    @Test
    @DisplayName("不传 size：data 是全量数组（不是分页包装）")
    void findAllWithoutSizeReturnsPlainArray() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_list");
        createChannel(auth, "ch_list_1", "biz_list", "列表通道1");
        createChannel(auth, "ch_list_2", "biz_list", "列表通道2");

        mockMvc.perform(get("/api/channels").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.channelId=='ch_list_1')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.channelId=='ch_list_2')]").isNotEmpty());
    }

    @Test
    @DisplayName("传 businessId：只返回该业务的通道")
    void findAllFiltersByBusiness() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_f1");
        createBusiness(auth, "biz_f2");
        createChannel(auth, "ch_f1_1", "biz_f1", "业务一通道");
        createChannel(auth, "ch_f1_2", "biz_f1", "业务一通道二");
        createChannel(auth, "ch_f2_1", "biz_f2", "业务二通道");

        mockMvc.perform(get("/api/channels").param("businessId", "biz_f1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.channelId=='ch_f2_1')]").isEmpty());
    }

    @Test
    @DisplayName("传 size：返回 {items,total,page,size} 分页包装")
    void findAllWithSizeReturnsPageResult() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_page");
        createChannel(auth, "ch_page_1", "biz_page", "分页通道1");
        createChannel(auth, "ch_page_2", "biz_page", "分页通道2");

        mockMvc.perform(get("/api/channels")
                        .param("businessId", "biz_page")
                        .param("page", "0")
                        .param("size", "1")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1));
    }

    @Test
    @DisplayName("size 越界 / page 为负：400 点名参数")
    void findAllRejectsInvalidPagingParams() throws Exception {
        String auth = bearer();

        mockMvc.perform(get("/api/channels").param("size", "0")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("size 必须在 1..")));

        mockMvc.perform(get("/api/channels").param("page", "-1").param("size", "10")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("page 不能为负")));
    }

    // ---------- 详情 / 更新 / 删除 ----------

    @Test
    @DisplayName("查详情：存在返回通道，不存在返回信封 404")
    void findByIdReturnsChannelOr404() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_detail");
        createChannel(auth, "ch_detail_1", "biz_detail", "详情通道");

        mockMvc.perform(get("/api/channels/ch_detail_1").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channelId").value("ch_detail_1"))
                .andExpect(jsonPath("$.data.channelName").value("详情通道"));

        mockMvc.perform(get("/api/channels/ch_missing_404").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message", containsString("Channel not found: ch_missing_404")));
    }

    @Test
    @DisplayName("更新改名后详情同步变化")
    void updateRenamesChannel() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_upd");
        createChannel(auth, "ch_upd_1", "biz_upd", "改名前");

        mockMvc.perform(put("/api/channels/ch_upd_1")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_upd_1\",\"channelName\":\"改名后\","
                                + "\"businessId\":\"biz_upd\",\"protocolType\":\"CUSTOM_TCP\","
                                + "\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.channelName").value("改名后"));

        mockMvc.perform(get("/api/channels/ch_upd_1").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channelName").value("改名后"));
    }

    @Test
    @DisplayName("删除后详情返回 404；对不存在的通道删除同样信封 404")
    void deleteRemovesChannel() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_del");
        createChannel(auth, "ch_del_1", "biz_del", "待删通道");

        mockMvc.perform(delete("/api/channels/ch_del_1").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/channels/ch_del_1").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/channels/ch_del_never_existed").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("断开未连接的通道：幂等成功")
    void disconnectIsIdempotent() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_disc");
        createChannel(auth, "ch_disc_1", "biz_disc", "断开通道");

        mockMvc.perform(post("/api/channels/ch_disc_1/disconnect").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
