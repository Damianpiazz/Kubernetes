package com.latiendita.productService.category;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.productService.TestcontainersConfiguration;
import com.latiendita.productService.category.dto.CategoryDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
class CategoryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void cleanDatabase() {
        categoryRepository.deleteAll();
    }

    @Test
    void shouldCreateCategoryReturning201() throws Exception {
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new CategoryDto(null, "Electrónica", "Devices"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/categories/")))
                .andExpect(jsonPath("$.categoryId").isNumber())
                .andExpect(jsonPath("$.name").value("Electrónica"));
    }

    @Test
    void shouldRejectInvalidCategoryWith400() throws Exception {
        mockMvc.perform(post("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new CategoryDto(null, "", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldReturn404WhenCategoryDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/categories/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Categoría no encontrada"));
    }

    @Test
    void shouldReturnCategoryById() throws Exception {
        CategoryEntity saved = categoryRepository.save(category("Electrónica", "Devices"));

        mockMvc.perform(get("/api/categories/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Electrónica"));
    }

    @Test
    void shouldPaginateCategoriesViaPaging() throws Exception {
        categoryRepository.saveAll(List.of(
                category("Cat1", null),
                category("Cat2", null),
                category("Cat3", null)));

        mockMvc.perform(get("/api/categories/paging").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get("/api/categories/paging").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void shouldPaginateAndSortViaPagingAndSorting() throws Exception {
        categoryRepository.saveAll(List.of(
                category("Beto", null),
                category("Alfa", null),
                category("Zeta", null)));

        mockMvc.perform(get("/api/categories/paging-and-sorting")
                        .param("pageNo", "0")
                        .param("pageSize", "3")
                        .param("sortBy", "name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Alfa"))
                .andExpect(jsonPath("$.content[2].name").value("Zeta"));

        mockMvc.perform(get("/api/categories/paging-and-sorting")
                        .param("sortBy", "categoryId"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldUpdateCategoryViaPutWithBodyId() throws Exception {
        CategoryEntity saved = categoryRepository.save(category("Vieja", null));

        mockMvc.perform(put("/api/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new CategoryDto(saved.getId(), "Nueva", "Nueva desc"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nueva"))
                .andExpect(jsonPath("$.description").value("Nueva desc"));
    }

    @Test
    void shouldUpdateCategoryViaPutWithPathVariable() throws Exception {
        CategoryEntity saved = categoryRepository.save(category("Vieja", null));

        mockMvc.perform(put("/api/categories/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new CategoryDto(null, "Nueva", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nueva"));
    }

    @Test
    void shouldDeleteCategoryThenReturn404() throws Exception {
        CategoryEntity saved = categoryRepository.save(category("Borrable", null));

        mockMvc.perform(delete("/api/categories/{id}", saved.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/categories/{id}", saved.getId()))
                .andExpect(status().isNotFound());
    }

    private String body(CategoryDto dto) throws Exception {
        return objectMapper.writeValueAsString(dto);
    }

    private CategoryEntity category(String name, String description) {
        return CategoryEntity.builder()
                .name(name)
                .description(description)
                .build();
    }
}
