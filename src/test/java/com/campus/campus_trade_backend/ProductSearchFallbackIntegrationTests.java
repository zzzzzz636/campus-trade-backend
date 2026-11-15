package com.campus.campus_trade_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_search_fallback;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.search.enabled=true", "campus.search.url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class ProductSearchFallbackIntegrationTests {
    @Autowired MockMvc mvc;

    @Test
    void unavailableIndexReturns503WhileMysqlListRemainsAvailable() throws Exception {
        mvc.perform(get("/api/products/search?q=keyboard")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/products?page=1&size=10&q=keyboard")).andExpect(status().isOk());
    }
}
