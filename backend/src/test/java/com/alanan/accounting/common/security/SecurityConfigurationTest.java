package com.alanan.accounting.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigurationTest.ProtectedController.class)
@Import(SecurityConfiguration.class)
class SecurityConfigurationTest {
    @Autowired MockMvc mvc;

    @Test
    void businessEndpointsAreClosedBeforeAuthenticationExists() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isForbidden());
    }

    @RestController
    static class ProtectedController {
        @GetMapping("/api/products")
        String products() { return "must not be exposed"; }
    }
}
