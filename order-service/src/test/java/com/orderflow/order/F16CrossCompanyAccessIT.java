package com.orderflow.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * F16 — Acceso entre empresas. Pedido de ACME: buyer-globex GET → 404 NOT_FOUND y su listado no lo
 * incluye; ops GET → 200.
 */
class F16CrossCompanyAccessIT extends AbstractIT {

  @Test
  void otraEmpresaNoVeElPedidoYOpsSi() throws Exception {
    stubPrices("SKU-0001", "9.00");
    String acmeOrder = createdOrderId("ACME", order(TODAY.plusDays(7), "SKU-0001", 2));
    // GLOBEX tiene su propio pedido: el listado no está vacío por casualidad.
    String globexOrder = createdOrderId("GLOBEX", order(TODAY.plusDays(7), "SKU-0001", 1));

    mvc.perform(get(ORDERS + "/" + acmeOrder).with(buyer("GLOBEX")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    mvc.perform(get(ORDERS).with(buyer("GLOBEX")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[*].orderId").value(Matchers.contains(globexOrder)))
        .andExpect(jsonPath("$.content[*].orderId").value(Matchers.not(Matchers.hasItem(acmeOrder))));

    mvc.perform(get(ORDERS + "/" + acmeOrder).with(ops()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value(acmeOrder))
        .andExpect(jsonPath("$.companyId").value("ACME"));
  }
}
