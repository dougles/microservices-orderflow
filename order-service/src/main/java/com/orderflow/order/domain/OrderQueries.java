package com.orderflow.order.domain;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecturas de pedidos (SPEC §5.2). {@code companyId} vacío = sin filtro de empresa (ops); un pedido
 * de otra empresa se trata como inexistente (SPEC §4).
 */
@Repository
public class OrderQueries {

  private static final String SELECT =
      """
      SELECT o.id, o.company_id, o.delivery_date, o.currency, o.total, o.created_at,
             s.state, s.failure_reason, s.updated_at
      FROM orders o JOIN saga s ON s.order_id = o.id
      """;

  private final NamedParameterJdbcTemplate jdbc;

  OrderQueries(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public OrderView get(UUID orderId, Optional<String> companyId) {
    var params = new MapSqlParameterSource("id", orderId);
    String where = " WHERE o.id = :id";
    if (companyId.isPresent()) {
      where += " AND o.company_id = :companyId";
      params.addValue("companyId", companyId.get());
    }
    List<Header> found = jdbc.query(SELECT + where, params, OrderQueries::header);
    if (found.isEmpty()) {
      throw new OrderNotFoundException(orderId);
    }
    return withLines(found).getFirst();
  }

  @Transactional(readOnly = true)
  public OrderView.Page list(
      Optional<String> companyId, Optional<PublicStatus> status, int page, int size) {
    var params = new MapSqlParameterSource();
    var conditions = new ArrayList<String>();
    companyId.ifPresent(
        id -> {
          conditions.add("o.company_id = :companyId");
          params.addValue("companyId", id);
        });
    status.ifPresent(
        s -> {
          conditions.add("s.state IN (:states)");
          params.addValue("states", s.states().stream().map(Enum::name).toList());
          if (!s.reasons().isEmpty()) {
            conditions.add("s.failure_reason IN (:reasons)");
            params.addValue("reasons", s.reasons().stream().map(Enum::name).toList());
          }
        });
    String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

    Long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM orders o JOIN saga s ON s.order_id = o.id" + where,
            params,
            Long.class);
    params.addValue("limit", size).addValue("offset", (long) page * size);
    List<Header> headers =
        jdbc.query(
            SELECT + where + " ORDER BY o.created_at DESC, o.id LIMIT :limit OFFSET :offset",
            params,
            OrderQueries::header);
    return new OrderView.Page(withLines(headers), page, size, total == null ? 0 : total);
  }

  private List<OrderView> withLines(List<Header> headers) {
    if (headers.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<OrderView.Line>> lines = new LinkedHashMap<>();
    headers.forEach(h -> lines.put(h.id(), new ArrayList<>()));
    jdbc.query(
        "SELECT order_id, sku, quantity, unit_price FROM order_line"
            + " WHERE order_id IN (:ids) ORDER BY order_id, sku",
        new MapSqlParameterSource("ids", List.copyOf(lines.keySet())),
        rs -> {
          lines
              .get(rs.getObject("order_id", UUID.class))
              .add(
                  new OrderView.Line(
                      rs.getString("sku"), rs.getInt("quantity"), rs.getBigDecimal("unit_price")));
        });
    return headers.stream().map(h -> h.toView(lines.get(h.id()))).toList();
  }

  private record Header(
      UUID id,
      String companyId,
      LocalDate deliveryDate,
      String currency,
      BigDecimal total,
      Instant createdAt,
      SagaState state,
      FailureReason reason,
      Instant updatedAt) {

    OrderView toView(List<OrderView.Line> lines) {
      PublicStatus status = PublicStatus.of(state, reason);
      return new OrderView(
          id,
          companyId,
          status,
          status.exposedReason(reason),
          deliveryDate,
          currency,
          total,
          List.copyOf(lines),
          createdAt,
          updatedAt);
    }
  }

  private static Header header(ResultSet rs, int row) throws SQLException {
    String reason = rs.getString("failure_reason");
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getString("company_id"),
        rs.getObject("delivery_date", LocalDate.class),
        rs.getString("currency"),
        rs.getBigDecimal("total"),
        rs.getTimestamp("created_at").toInstant(),
        SagaState.valueOf(rs.getString("state")),
        reason == null ? null : FailureReason.valueOf(reason),
        rs.getTimestamp("updated_at").toInstant());
  }
}
