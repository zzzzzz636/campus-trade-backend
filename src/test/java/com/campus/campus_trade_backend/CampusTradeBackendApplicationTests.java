package com.campus.campus_trade_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CampusTradeBackendApplicationTests {
	@Autowired
	private ProductService productService;
	@Autowired
	private MockMvc mvc;

	@Test
	void contextLoads() {
	}

	@Test
	void productListReadsSeededDatabase() {
		var page = productService.list(1, 1);
		assertEquals(2, page.total());
		assertEquals(2L, page.items().get(0).id());
		assertEquals("二手台灯", page.items().get(0).title());
		assertTrue(productService.findById(1L).isPresent());
	}

	@Test
	void openApiDocumentIsPubliclyAvailable() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("CampusTrade API"))
				.andExpect(jsonPath("$.info.version").value("V4"));
	}

}
