package com.kervan.catalog.web;

import com.kervan.catalog.AbstractMongoIntegrationTest;
import com.kervan.catalog.security.TestJwtSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Katalogu kimin değiştirebileceği.
 *
 * <p>Bu testler bir güvenlik açığından sonra yazıldı. Servis hiçbir yetki denetimi
 * yapmıyordu. Ağ geçidi eklendiğinde (ADR-0007) katalog belgelenmiş ön kapının
 * arkasına kondu, ama ağ geçidi yalnızca <em>kimlik</em> doğruluyordu. Sonuç:
 * CUSTOMER rolüyle alınmış herhangi bir token
 * {@code DELETE /api/v1/products/{id}} çağırıp ürünü kalıcı silebiliyordu.
 *
 * <p>Buradaki iddiaların hepsi tek bir soruyu soruyor: <b>katalogu kim değiştirebilir?</b>
 */
class ProductSecurityTest extends AbstractMongoIntegrationTest {

    private static final String NEW_PRODUCT = """
            {
              "sku": "SKU-SEC-1",
              "name": "Guvenlik Testi Urunu",
              "description": "aciklama",
              "brand": "Kervan",
              "categoryPath": "test/guvenlik",
              "price": { "amount": 10.00, "currency": "TRY" }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Urun okuma tokensiz calisir: katalog bir vitrindir")
    void readsArePublic() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Tokensiz urun olusturulamaz")
    void anonymousCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("CUSTOMER urun OLUSTURAMAZ")
    void customerCannotCreate() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtSupport.tokenFor("musteri-1", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER urunu KALICI SILEMEZ -- acigin can alici noktasi buydu")
    void customerCannotDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/products/herhangi-bir-id")
                        .header("Authorization", "Bearer " + TestJwtSupport.tokenFor("musteri-1", "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CUSTOMER urunu guncelleyemez, yayina alamaz, arsivleyemez")
    void customerCannotChangeState() throws Exception {
        String customer = "Bearer " + TestJwtSupport.tokenFor("musteri-1", "CUSTOMER");

        mockMvc.perform(put("/api/v1/products/bir-id")
                        .header("Authorization", customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/products/bir-id/activate")
                        .header("Authorization", customer))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/products/bir-id/archive")
                        .header("Authorization", customer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN urun olusturabilir")
    void adminCanCreate() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtSupport.tokenFor("yonetici-1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Suresi gecmis ADMIN token'i reddedilir")
    void expiredAdminTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtSupport.expiredTokenFor("yonetici-1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Yabanci anahtarla imzalanmis ADMIN token'i reddedilir")
    void foreignSignatureIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header("Authorization", "Bearer " + TestJwtSupport.tokenSignedByStranger("x", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(NEW_PRODUCT))
                .andExpect(status().isUnauthorized());
    }
}
