package com.orderflow.catalogimportfn;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderflow.catalogimportfn.CatalogCsv.Error;
import com.orderflow.catalogimportfn.CatalogCsv.Row;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Todas las razones de SPEC §11 y las reglas de producto de SPEC §9. */
class CatalogCsvTest {

  private static String csv(String... rows) {
    return CatalogCsv.HEADER + "\n" + String.join("\n", rows) + "\n";
  }

  @Test
  void filasValidasConSuNumeroDeLinea() {
    var result = CatalogCsv.parse(csv("NEW-001,Tornillo,10.50", "NEW-002,Tuerca,3"));

    assertThat(result.errors()).isEmpty();
    assertThat(result.valid())
        .containsExactly(
            new Row("NEW-001", "Tornillo", new BigDecimal("10.50"), 2),
            new Row("NEW-002", "Tuerca", new BigDecimal("3"), 3));
  }

  @Test
  void elEscenarioDeF15DaExactamenteDosErrores() {
    var result =
        CatalogCsv.parse(
            csv(
                "F15-A,Producto A,10.00",
                "F15-B,Producto B,20.00",
                "F15-BAD,Precio negativo,-1",
                "F15-C,Producto C,30.00",
                "F15-A,Repetido,40.00"));

    assertThat(result.valid()).extracting(Row::sku).containsExactly("F15-A", "F15-B", "F15-C");
    assertThat(result.errors())
        .containsExactly(new Error(4, "INVALID_PRICE"), new Error(6, "DUPLICATE_SKU_IN_FILE"));
  }

  @Test
  void laTerceraAparicionTambienEsDuplicada() {
    var result = CatalogCsv.parse(csv("DUP-1,A,1.00", "DUP-1,B,2.00", "DUP-1,C,3.00"));

    assertThat(result.valid()).extracting(Row::line).containsExactly(2);
    assertThat(result.errors())
        .containsExactly(
            new Error(3, "DUPLICATE_SKU_IN_FILE"), new Error(4, "DUPLICATE_SKU_IN_FILE"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "ab-1,Nombre,1.00        | INVALID_SKU",
        "AB,Nombre,1.00          | INVALID_SKU",
        "SKU_1,Nombre,1.00       | INVALID_SKU",
        "SKU-0001,,1.00          | INVALID_NAME",
        "SKU-0001,Con,coma,1.00  | INVALID_NAME",
        "SKU-0001                | INVALID_NAME",
        "SKU-0001,Nombre         | INVALID_PRICE",
        "SKU-0001,Nombre,0       | INVALID_PRICE",
        "SKU-0001,Nombre,-1      | INVALID_PRICE",
        "SKU-0001,Nombre,1.001   | INVALID_PRICE",
        "SKU-0001,Nombre,abc     | INVALID_PRICE",
        "SKU-0001,Nombre,1e3     | INVALID_PRICE",
        "SKU-0001,Nombre,12345678901 | INVALID_PRICE",
      })
  void cadaFilaInvalidaTieneSuRazon(String row, String reason) {
    var result = CatalogCsv.parse(csv(row.strip()));

    assertThat(result.valid()).isEmpty();
    assertThat(result.errors()).containsExactly(new Error(2, reason));
  }

  @Test
  void unNombreDe200EsValidoYDe201No() {
    String ok = "N".repeat(200);
    String tooLong = "N".repeat(201);

    var result = CatalogCsv.parse(csv("LEN-1," + ok + ",1.00", "LEN-2," + tooLong + ",1.00"));

    assertThat(result.valid()).extracting(Row::sku).containsExactly("LEN-1");
    assertThat(result.errors()).containsExactly(new Error(3, "INVALID_NAME"));
  }

  @Test
  void ceroDecimalesExtraNoInvalidanElPrecio() {
    var result = CatalogCsv.parse(csv("ZER-1,Nombre,10.500"));

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void encabezadoDistintoRechazaElArchivoEntero() {
    var result = CatalogCsv.parse("sku,nombre,basePrice\nSKU-0001,Nombre,1.00\n");

    assertThat(result.valid()).isEmpty();
    assertThat(result.errors()).containsExactly(new Error(1, "INVALID_HEADER"));
  }

  @Test
  void archivoVacioTieneEncabezadoInvalido() {
    assertThat(CatalogCsv.parse("").errors()).containsExactly(new Error(1, "INVALID_HEADER"));
  }

  @Test
  void masDeDiezMilFilasRechazaElArchivoEntero() {
    var rows = new StringBuilder(CatalogCsv.HEADER).append('\n');
    for (int i = 0; i <= CatalogCsv.MAX_ROWS; i++) {
      rows.append("ROW-").append(i).append(",Nombre,1.00\n");
    }

    var result = CatalogCsv.parse(rows.toString());

    assertThat(result.valid()).isEmpty();
    assertThat(result.errors()).containsExactly(new Error(0, "TOO_MANY_ROWS"));
  }

  @Test
  void diezMilFilasSeAceptan() {
    var rows = new StringBuilder(CatalogCsv.HEADER).append('\n');
    for (int i = 0; i < CatalogCsv.MAX_ROWS; i++) {
      rows.append("ROW-").append(i).append(",Nombre,1.00\n");
    }

    assertThat(CatalogCsv.parse(rows.toString()).valid()).hasSize(CatalogCsv.MAX_ROWS);
  }

  @Test
  void toleraBomYFinesDeLineaWindows() {
    var result = CatalogCsv.parse("﻿" + CatalogCsv.HEADER + "\r\nWIN-1,Nombre,1.00\r\n");

    assertThat(result.errors()).isEmpty();
    assertThat(result.valid()).extracting(Row::sku).containsExactly("WIN-1");
  }

  @Test
  void archivoDeErroresConEncabezadoLineReason() {
    String text =
        CatalogCsv.errorsCsv(
            java.util.List.of(new Error(4, "INVALID_PRICE"), new Error(6, "DUPLICATE_SKU_IN_FILE")));

    assertThat(text).isEqualTo("line,reason\n4,INVALID_PRICE\n6,DUPLICATE_SKU_IN_FILE\n");
  }

  @Test
  void elArchivoDeErroresConservaElNombreOriginal() {
    assertThat(CatalogCsv.errorsKey("incoming/f15-abc.csv")).isEqualTo("errors/f15-abc.csv.errors.csv");
  }
}
