package com.orderflow.e2e.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC a {@code localhost:5432}, una base por servicio con su propio usuario (SPEC §3.3). Para
 * deltas y preparación de datos; las aserciones de resultado van por la API.
 */
public final class Db {

  public enum Database {
    CATALOG("catalog", "CATALOG_DB_PASSWORD"),
    ORDERS("orders", "ORDERS_DB_PASSWORD"),
    INVENTORY("inventory", "INVENTORY_DB_PASSWORD"),
    CREDIT("credit", "CREDIT_DB_PASSWORD"),
    SHIPPING("shipping", "SHIPPING_DB_PASSWORD");

    private final String name;
    private final String passwordVariable;

    Database(String name, String passwordVariable) {
      this.name = name;
      this.passwordVariable = passwordVariable;
    }
  }

  private Db() {}

  public static Connection connect(Database db) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://localhost:5432/" + db.name, db.name, Env.get(db.passwordVariable));
  }

  public static List<Map<String, Object>> query(Database db, String sql, Object... params) {
    try (Connection c = connect(db);
        PreparedStatement ps = prepare(c, sql, params);
        ResultSet rs = ps.executeQuery()) {
      var rows = new ArrayList<Map<String, Object>>();
      int columns = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= columns; i++) {
          row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        rows.add(row);
      }
      return rows;
    } catch (SQLException e) {
      throw new IllegalStateException("query en " + db.name + " falló: " + sql, e);
    }
  }

  /** Primera columna de la primera fila; {@code null} si no hay filas. */
  public static Object scalar(Database db, String sql, Object... params) {
    List<Map<String, Object>> rows = query(db, sql, params);
    return rows.isEmpty() ? null : rows.getFirst().values().iterator().next();
  }

  public static long count(Database db, String sql, Object... params) {
    Object value = scalar(db, sql, params);
    return value == null ? 0 : ((Number) value).longValue();
  }

  public static int update(Database db, String sql, Object... params) {
    try (Connection c = connect(db);
        PreparedStatement ps = prepare(c, sql, params)) {
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("update en " + db.name + " falló: " + sql, e);
    }
  }

  private static PreparedStatement prepare(Connection c, String sql, Object... params)
      throws SQLException {
    PreparedStatement ps = c.prepareStatement(sql);
    for (int i = 0; i < params.length; i++) {
      ps.setObject(i + 1, params[i]);
    }
    return ps;
  }
}
