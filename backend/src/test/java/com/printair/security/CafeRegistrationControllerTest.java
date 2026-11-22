package com.printair.security;

import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CafeRegistrationController.class, com.printair.shop.PublicShopController.class})
@Import(SecurityConfiguration.class)
@TestPropertySource(properties = "printair.cors.allowed-origins=http://localhost")
class CafeRegistrationControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean CafeRegistrationService registration;
    @MockBean ShopRepository shops;
    @MockBean OperatorUserDetailsService users;
    @MockBean RateLimitService rateLimit;

    @BeforeEach
    void allowRequestsThroughRateLimitFilter() {
        when(rateLimit.allow(anyString(), anyInt(), anyLong())).thenReturn(true);
    }

    @Test
    void exposesRegistrationAndVerificationWithoutOperatorAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"shopName":"Paper Corner","email":"owner@example.test","password":"long-password"}
                                """))
                .andExpect(status().isNoContent());
        verify(registration).register("Paper Corner", "owner@example.test", "long-password");

        mockMvc.perform(post("/api/auth/verify-email").with(csrf()).contentType(APPLICATION_JSON)
                        .content("{\"token\":\"one-time-token\"}"))
                .andExpect(status().isNoContent());
        verify(registration).verify("one-time-token");
    }

    @Test
    void rejectsRegistrationWithShortPasswords() throws Exception {
        mockMvc.perform(post("/api/auth/register").with(csrf()).contentType(APPLICATION_JSON)
                        .content("""
                                {"shopName":"Paper Corner","email":"owner@example.test","password":"short"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(registration);
    }

    @Test
    void returnsOnlyTheShopNameFromThePublicProfile() throws Exception {
        when(shops.findBySlug("paper-corner-abc123")).thenReturn(
                Optional.of(new Shop("Paper Corner", "paper-corner-abc123")));

        mockMvc.perform(get("/api/public/shops/paper-corner-abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("Paper Corner"))
                .andExpect(jsonPath("$.slug").doesNotExist());
    }

    @Test
    void returnsNotFoundForUnknownPublicShopSlugs() throws Exception {
        when(shops.findBySlug("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/public/shops/unknown"))
                .andExpect(status().isNotFound());
    }
}
