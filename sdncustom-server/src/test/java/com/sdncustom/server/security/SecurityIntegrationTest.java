package com.sdncustom.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(200, body.get("code").asInt());
        return body.get("data").get("token").asText();
    }

    @Test
    void unauthenticatedRequest_returns401WithApiResponseFormat() throws Exception {
        mockMvc.perform(get("/api/channels"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Unauthorized"));
    }

    @Test
    void loginWithValidCredentials_returnsToken() throws Exception {
        String token = login("testadmin", "testpass");
        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void loginWithWrongPassword_returnsBusinessError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"testadmin\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void authenticatedRequest_returns200() throws Exception {
        String token = login("testadmin", "testpass");
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/channels").header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meEndpoint_returnsCurrentUsername() throws Exception {
        String token = login("testadmin", "testpass");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("testadmin"));
    }

    @Test
    void actuatorHealth_isPublicWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void systemStatus_requiresAuth_thenReturnsAggregate() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isUnauthorized());

        String token = login("testadmin", "testpass");
        mockMvc.perform(get("/api/system/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channelsTotal").exists())
                .andExpect(jsonPath("$.data.uptimeSeconds").exists());
    }

    /** 端到端钉住错误契约：枚举查询参数的非法值必须走 400，而不是兜底的 500 */
    @Test
    void invalidDirectionQueryParam_returnsBadRequestNot500() throws Exception {
        String token = login("testadmin", "testpass");

        mockMvc.perform(get("/api/points").param("direction", "FOO")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(containsString("direction")));
    }

    @Test
    void validDirectionQueryParam_returns200() throws Exception {
        String token = login("testadmin", "testpass");

        mockMvc.perform(get("/api/points").param("direction", "INPUT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
