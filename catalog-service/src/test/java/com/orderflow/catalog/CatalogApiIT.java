package com.orderflow.catalog;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class CatalogApiIT extends AbstractIT {

  private static final String PRODUCTS = "/api/catalog/products";
  private static final String PRICES = "/internal/prices";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void restoreData() {
    jdbc.update("UPDATE product SET active = TRUE");
    jdbc.update("DELETE FROM product WHERE sku LIKE 'IT-%'");
  }

  @Test
  void elCompradorVeLosPreciosConElDescuentoDeSuEmpresa() throws Exception {
    mvc.perform(get(PRODUCTS).with(buyer("ACME")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalElements").value(20))
        .andExpect(jsonPath("$.content.length()").value(20))
        .andExpect(jsonPath("$.content[0].sku").value("SKU-0001"))
        .andExpect(jsonPath("$.content[0].name").value("Producto 0001"))
        .andExpect(jsonPath("$.content[0].unitPrice").value("9.00"))
        .andExpect(jsonPath("$.content[19].sku").value("SKU-0020"))
        .andExpect(jsonPath("$.content[19].unitPrice").value("180.00"));
  }

  @Test
  void lasSemillasDefinenLosDescuentosDeCadaEmpresa() throws Exception {
    mvc.perform(get(PRODUCTS).with(buyer("GLOBEX")))
        .andExpect(jsonPath("$.content[0].unitPrice").value("10.00"));
    mvc.perform(get(PRODUCTS).with(buyer("INITECH")))
        .andExpect(jsonPath("$.content[0].unitPrice").value("9.50"));
  }

  @Test
  void unaEmpresaSinDescuentoRegistradoPagaElPrecioBase() throws Exception {
    mvc.perform(get(PRODUCTS).with(buyer("SIN-DESCUENTO")))
        .andExpect(jsonPath("$.content[0].unitPrice").value("10.00"));
  }

  @Test
  void opsVeElPrecioBase() throws Exception {
    mvc.perform(get(PRODUCTS).with(ops()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].unitPrice").value("10.00"));
  }

  @Test
  void laQueryNoPuedeCambiarLaEmpresaDelToken() throws Exception {
    mvc.perform(get(PRODUCTS).param("companyId", "GLOBEX").with(buyer("ACME")))
        .andExpect(jsonPath("$.content[0].unitPrice").value("9.00"));
  }

  @Test
  void paginaOrdenandoPorSku() throws Exception {
    mvc.perform(get(PRODUCTS).param("page", "1").param("size", "5").with(ops()))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(5))
        .andExpect(jsonPath("$.totalElements").value(20))
        .andExpect(jsonPath("$.content.length()").value(5))
        .andExpect(jsonPath("$.content[0].sku").value("SKU-0006"));
  }

  @Test
  void soloListaProductosActivos() throws Exception {
    jdbc.update("UPDATE product SET active = FALSE WHERE sku = 'SKU-0001'");

    mvc.perform(get(PRODUCTS).with(ops()))
        .andExpect(jsonPath("$.totalElements").value(19))
        .andExpect(jsonPath("$.content[0].sku").value("SKU-0002"));
  }

  @Test
  void unaPaginacionInvalidaResponde400() throws Exception {
    expectValidationError(get(PRODUCTS).param("page", "-1").with(ops()));
    expectValidationError(get(PRODUCTS).param("size", "0").with(ops()));
    expectValidationError(get(PRODUCTS).param("page", "abc").with(ops()));
  }

  @Test
  void losPreciosInternosAplicanElDescuentoEInformanLosDesconocidos() throws Exception {
    jdbc.update("UPDATE product SET active = FALSE WHERE sku = 'SKU-0003'");

    mvc.perform(
            get(PRICES)
                .param("companyId", "ACME")
                .param("skus", "SKU-0002,SKU-9999,SKU-0001,SKU-0003")
                .with(internalClient()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("BOB"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].sku").value("SKU-0002"))
        .andExpect(jsonPath("$.items[0].unitPrice").value("18.00"))
        .andExpect(jsonPath("$.items[1].sku").value("SKU-0001"))
        .andExpect(jsonPath("$.items[1].unitPrice").value("9.00"))
        .andExpect(jsonPath("$.unknownSkus.length()").value(2))
        .andExpect(jsonPath("$.unknownSkus[0]").value("SKU-9999"))
        .andExpect(jsonPath("$.unknownSkus[1]").value("SKU-0003"));
  }

  @Test
  void losPreciosInternosRedondeanConHalfEven() throws Exception {
    jdbc.update("INSERT INTO product (sku, name, base_price) VALUES ('IT-TIE', 'Empate', 1.25)");
    jdbc.update(
        """
        INSERT INTO company_discount (company_id, discount_pct) VALUES ('IT-HALF', 50)
        ON CONFLICT DO NOTHING
        """);

    mvc.perform(
            get(PRICES).param("companyId", "IT-HALF").param("skus", "IT-TIE").with(internalClient()))
        .andExpect(jsonPath("$.items[0].unitPrice").value("0.62"));
  }

  @Test
  void losPreciosInternosExigenSusParametros() throws Exception {
    expectValidationError(get(PRICES).param("skus", "SKU-0001").with(internalClient()));
    expectValidationError(get(PRICES).param("companyId", "ACME").with(internalClient()));
    expectValidationError(
        get(PRICES).param("companyId", "ACME").param("skus", "").with(internalClient()));
  }

  @Test
  void sinTokenResponde401() throws Exception {
    mvc.perform(get(PRODUCTS)).andExpect(status().isUnauthorized());
    mvc.perform(get(PRICES).param("companyId", "ACME").param("skus", "SKU-0001"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void laMatrizDeAutorizacionSeRespeta() throws Exception {
    mvc.perform(get(PRODUCTS).with(internalClient())).andExpect(status().isForbidden());
    mvc.perform(get(PRICES).param("companyId", "ACME").param("skus", "SKU-0001").with(buyer("ACME")))
        .andExpect(status().isForbidden());
    mvc.perform(get(PRICES).param("companyId", "ACME").param("skus", "SKU-0001").with(ops()))
        .andExpect(status().isForbidden());
  }

  @Test
  void laSaludYLasMetricasNoExigenToken() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  private void expectValidationError(MockHttpServletRequestBuilder request) throws Exception {
    mvc.perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static JwtRequestPostProcessor buyer(String companyId) {
    return jwt()
        .jwt(token -> token.claim("company_id", companyId))
        .authorities(new SimpleGrantedAuthority("ROLE_buyer"));
  }

  private static JwtRequestPostProcessor ops() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_ops"));
  }

  private static JwtRequestPostProcessor internalClient() {
    return jwt().authorities(new SimpleGrantedAuthority("SCOPE_catalog.internal"));
  }
}
