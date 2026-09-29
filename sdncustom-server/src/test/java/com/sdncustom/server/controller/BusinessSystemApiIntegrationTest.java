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
 * 业务接口的 HTTP 契约：@Valid 校验、404 语义、删除前置校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("业务接口 HTTP 契约 集成测试")
class BusinessSystemApiIntegrationTest {

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

    private void createBusiness(String auth, String id) throws Exception {
        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"" + id + "\",\"businessName\":\"" + id + " 名称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("列表查询：信封内返回已创建的业务")
    void findAllContainsCreatedBusiness() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_list_it");

        mockMvc.perform(get("/api/businesses").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[?(@.businessId=='biz_list_it')]").isNotEmpty());
    }

    @Test
    @DisplayName("查详情：存在返回数据，不存在信封 404 点名资源")
    void findByIdReturnsBusinessOr404() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_detail_it");

        mockMvc.perform(get("/api/businesses/biz_detail_it").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.businessId").value("biz_detail_it"));

        mockMvc.perform(get("/api/businesses/biz_missing_it").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message",
                        containsString("BusinessSystem not found: biz_missing_it")));
    }

    @Test
    @DisplayName("创建：成功入库；businessId 重复返回 400 点名 id")
    void createAndDuplicateRejected() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_dup_it");

        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_dup_it\",\"businessName\":\"再来一次\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("业务已存在: biz_dup_it")));
    }

    @Test
    @DisplayName("创建缺 businessName：@Valid 拦成 400 并点名字段")
    void createRequiresBusinessName() throws Exception {
        String auth = bearer();

        mockMvc.perform(post("/api/businesses")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_noname_it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("businessName")));
    }

    @Test
    @DisplayName("更新改名生效；更新不存在的业务 404")
    void updateRenamesBusiness() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_upd_it");

        mockMvc.perform(put("/api/businesses/biz_upd_it")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_upd_it\",\"businessName\":\"改名后\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.businessName").value("改名后"));

        mockMvc.perform(put("/api/businesses/biz_gone_it")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessId\":\"biz_gone_it\",\"businessName\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("删除：名下有通道时 400 拦住并点名通道数")
    void deleteBlockedWhenChannelsExist() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_del_it");
        mockMvc.perform(post("/api/channels")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"ch_for_del_it\",\"channelName\":\"占位\","
                                + "\"businessId\":\"biz_del_it\",\"protocolType\":\"CUSTOM_TCP\","
                                + "\"autoConnect\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/businesses/biz_del_it").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("该业务下还有 1 个通道")));
    }

    @Test
    @DisplayName("删除空业务成功；再删一次信封 404")
    void deleteEmptyBusinessThen404() throws Exception {
        String auth = bearer();
        createBusiness(auth, "biz_del2_it");

        mockMvc.perform(delete("/api/businesses/biz_del2_it").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/businesses/biz_del2_it").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("未认证访问被拒（401，非信封）")
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/businesses"))
                .andExpect(status().isUnauthorized());
    }
}
