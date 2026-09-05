package com.kervan.catalog.web;

import com.kervan.catalog.AbstractMongoIntegrationTest;
import com.kervan.catalog.security.TestJwtSupport;
import com.kervan.catalog.infrastructure.persistence.SpringDataProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ürün API'sinin uçtan uca entegrasyon testleri — gerçek MongoDB'ye karşı.
 * Katmanların tamamını (web → application → domain → Mongo adaptörü) ve Mongock
 * indekslerini birlikte doğrular.
 */
class ProductControllerIntegrationTest extends AbstractMongoIntegrationTest {

    /**
     * Katalogu degistiren her istek yonetici ister (SecurityConfig).
     * Testler bunu tasimazsa 403 alir -- ve bu dogru davranistir.
     */
    private static final String ADMIN = "Bearer " + TestJwtSupport.tokenFor("yonetici-test", "ADMIN");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SpringDataProductRepository repository;

    @BeforeEach
    void cleanCollection() {
        // Testler arası izolasyon: her testten önce koleksiyonu temizle
        repository.deleteAll();
    }

    private static final String PHONE_JSON = """
            {
              "sku": "PHN-001",
              "name": "Kervan Akıllı Telefon X",
              "description": "6.5 inç ekran",
              "brand": "Kervan",
              "categoryPath": "elektronik/telefon",
              "price": { "amount": 14999.90, "currency": "TRY" },
              "attributes": { "ram": "8GB", "renk": "siyah", "ekran": "6.5" }
            }
            """;

    @Test
    void createProduct_returns201_withLocation_andDraftStatus() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PHONE_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sku", is("PHN-001")))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                // MongoDB'nin esnek belge modeli: kategoriye özgü öznitelikler korunur
                .andExpect(jsonPath("$.attributes.ram", is("8GB")))
                .andExpect(jsonPath("$.attributes.renk", is("siyah")));
    }

    @Test
    void getProduct_returns200_afterCreate() throws Exception {
        String location = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PHONE_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku", is("PHN-001")))
                .andExpect(jsonPath("$.price.amount", is(14999.90)))
                .andExpect(jsonPath("$.price.currency", is("TRY")));
    }

    @Test
    void getMissingProduct_returns404_asProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/products/nonexistent-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Ürün bulunamadı")))
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void duplicateSku_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PHONE_JSON))
                .andExpect(status().isCreated());

        // Aynı SKU ile ikinci istek → 409 Conflict
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PHONE_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));
    }

    @Test
    void missingRequiredField_returns400_withFieldErrors() throws Exception {
        String invalid = """
                { "sku": "X-1", "brand": "Kervan", "categoryPath": "a/b",
                  "price": { "amount": 10.0, "currency": "TRY" } }
                """; // name yok → doğrulama hatası
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Doğrulama hatası")))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void activate_thenList_filtersByStatus() throws Exception {
        String location = mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PHONE_JSON))
                .andReturn().getResponse().getHeader("Location");

        // DRAFT → ACTIVE
        mockMvc.perform(post(location + "/activate")
                        .header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));

        // ACTIVE filtresiyle listede 1 ürün görünür
        mockMvc.perform(get("/api/v1/products").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.items", hasSize(1)));

        // DRAFT filtresiyle liste boş (ürün artık ACTIVE)
        mockMvc.perform(get("/api/v1/products").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)));
    }
}
