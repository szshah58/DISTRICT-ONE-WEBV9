package com.districtone.webv9.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class PlotInventoryService {
  private static final Set<String> AVAILABLE_STATUSES = Set.of("AVAILABLE", "UNALLOCATED", "OPEN");
  private static final Set<String> VALID_STATUSES = Set.of(
      "AVAILABLE", "UNALLOCATED", "OPEN", "ALLOCATED", "BOOKED", "SOLD", "INACTIVE");
  private static final String AVAILABLE_PLOTS_SQL = """
      SELECT p.plot_id AS id, pr.id AS projectId, pr.project_code AS projectCode, pr.project_name AS projectName,
             b.block_id AS blockId, b.block_code AS block, b.block_name AS blockName, p.plot_no AS plotNo,
             p.plot_size_marla AS sizeMarla, p.plot_type AS type, p.category,
             p.status AS plotStatus, sb.sale_status AS saleStatus,
             CASE
               WHEN pending_cancellation.id IS NOT NULL OR pending_merge.plot_id IS NOT NULL THEN 'PENDING_REVIEW'
               WHEN sm.source_plot_id IS NOT NULL THEN 'MERGED'
               WHEN UPPER(COALESCE(sb.sale_status, '')) = 'CANCELLED' THEN 'CANCELLED'
               WHEN UPPER(COALESCE(sb.sale_status, '')) = 'SOLD' THEN 'SOLD'
               WHEN UPPER(COALESCE(sb.sale_status, '')) IN ('ALLOCATED','BOOKED') THEN 'BOOKED'
               WHEN tr.id IS NOT NULL THEN 'TRANSFERRED'
               ELSE p.status
             END AS status,
             CASE
               WHEN pending_cancellation.id IS NOT NULL OR pending_merge.plot_id IS NOT NULL THEN 'COMMITTED'
               WHEN sm.source_plot_id IS NOT NULL THEN 'AVAILABLE'
               WHEN sb.sale_booking_id IS NOT NULL AND UPPER(COALESCE(sb.sale_status, '')) NOT IN ('CANCELLED','MERGED') THEN 'COMMITTED'
               WHEN UPPER(COALESCE(sb.sale_status, '')) = 'CANCELLED'
                    AND UPPER(p.status) IN ('AVAILABLE','UNALLOCATED','OPEN') THEN 'AVAILABLE'
               WHEN tr.id IS NOT NULL THEN 'COMMITTED'
               WHEN UPPER(p.status) IN ('AVAILABLE','UNALLOCATED','OPEN') THEN 'AVAILABLE'
               ELSE 'UNAVAILABLE'
             END AS availability,
             CONCAT('SB-', LPAD(sb.sale_booking_id, 6, '0')) AS bookingNo,
             r.reg_no AS regNo, m.mbr_no AS mbrNo,
             CASE WHEN UPPER(COALESCE(sb.sale_status, '')) = 'CANCELLED' THEN NULL ELSE m.name END AS ownerName,
             tr.transfer_date AS lastTransferDate, ca.cancellation_date AS cancellationDate,
             CASE WHEN tm.target_plot_id IS NOT NULL THEN 'TARGET' ELSE NULL END AS mergeRole
      FROM plots p JOIN blocks b ON b.block_id = p.block_id
      LEFT JOIN webv9_projects pr ON pr.id = b.project_id
      LEFT JOIN (SELECT plot_id, MAX(sale_booking_id) AS booking_id
                 FROM sales_bookings GROUP BY plot_id) latest_booking ON latest_booking.plot_id = p.plot_id
      LEFT JOIN sales_bookings sb ON sb.sale_booking_id = latest_booking.booking_id
      LEFT JOIN registrations r ON r.registration_id = sb.registration_id
      LEFT JOIN members m ON m.member_id = r.member_id
      LEFT JOIN (SELECT booking_id, MAX(id) AS transfer_id FROM webv9_transfers
                 WHERE UPPER(status) = 'APPROVED' GROUP BY booking_id) latest_transfer
        ON latest_transfer.booking_id = sb.sale_booking_id
      LEFT JOIN webv9_transfers tr ON tr.id = latest_transfer.transfer_id
      LEFT JOIN (SELECT booking_id, MAX(id) AS cancellation_id FROM webv9_cancellations
                 WHERE UPPER(status) IN ('APPROVED','CANCELLED') GROUP BY booking_id) latest_cancellation
        ON latest_cancellation.booking_id = sb.sale_booking_id
      LEFT JOIN webv9_cancellations ca ON ca.id = latest_cancellation.cancellation_id
      LEFT JOIN (SELECT booking_id, MAX(id) AS cancellation_id FROM webv9_cancellations
                 WHERE UPPER(status) = 'PENDING' GROUP BY booking_id) pending_cancellation_row
        ON pending_cancellation_row.booking_id = sb.sale_booking_id
      LEFT JOIN webv9_cancellations pending_cancellation
        ON pending_cancellation.id = pending_cancellation_row.cancellation_id
      LEFT JOIN (
        SELECT plot_id, MAX(id) AS merge_id FROM (
          SELECT source_plot_id AS plot_id, id FROM webv9_plot_merges WHERE UPPER(status) = 'PENDING'
          UNION ALL
          SELECT target_plot_id AS plot_id, id FROM webv9_plot_merges WHERE UPPER(status) = 'PENDING'
        ) pending_plot_merges GROUP BY plot_id
      ) pending_merge ON pending_merge.plot_id = p.plot_id
      LEFT JOIN (SELECT source_plot_id, MAX(id) AS merge_id FROM webv9_plot_merges
                 WHERE UPPER(status) = 'APPROVED' GROUP BY source_plot_id) latest_source_merge
        ON latest_source_merge.source_plot_id = p.plot_id
      LEFT JOIN webv9_plot_merges sm ON sm.id = latest_source_merge.merge_id
      LEFT JOIN (SELECT target_plot_id, MAX(id) AS merge_id FROM webv9_plot_merges
                 WHERE UPPER(status) = 'APPROVED' GROUP BY target_plot_id) latest_target_merge
        ON latest_target_merge.target_plot_id = p.plot_id
      LEFT JOIN webv9_plot_merges tm ON tm.id = latest_target_merge.merge_id
      """;

  private final JdbcTemplate jdbc;

  public PlotInventoryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> list(String q, String status, String availability) {
    return list(q, status, availability, null);
  }

  public List<Map<String, Object>> list(String q, String status, String availability, Long projectId) {
    StringBuilder sql = new StringBuilder(AVAILABLE_PLOTS_SQL).append(" WHERE 1=1 ");
    List<Object> params = new ArrayList<>();
    if (projectId != null) {
      sql.append(" AND b.project_id = ? ");
      params.add(projectId);
    }
    if (q != null && !q.isBlank()) {
      sql.append(" AND (p.plot_no LIKE ? OR b.block_code LIKE ? OR b.block_name LIKE ?) ");
      String term = "%" + q.trim() + "%";
      params.add(term);
      params.add(term);
      params.add(term);
    }
    if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
      sql.append("""
           AND CASE
             WHEN pending_cancellation.id IS NOT NULL OR pending_merge.plot_id IS NOT NULL THEN 'PENDING_REVIEW'
             WHEN sm.source_plot_id IS NOT NULL THEN 'MERGED'
             WHEN UPPER(COALESCE(sb.sale_status, '')) = 'CANCELLED' THEN 'CANCELLED'
             WHEN UPPER(COALESCE(sb.sale_status, '')) = 'SOLD' THEN 'SOLD'
             WHEN UPPER(COALESCE(sb.sale_status, '')) IN ('ALLOCATED','BOOKED') THEN 'BOOKED'
             WHEN tr.id IS NOT NULL THEN 'TRANSFERRED'
             ELSE p.status
           END = ?
          """);
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (availability != null && !availability.isBlank() && !"ALL".equalsIgnoreCase(availability)) {
      if (!Set.of("AVAILABLE", "COMMITTED", "UNAVAILABLE").contains(availability.trim().toUpperCase(Locale.ROOT))) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported plot availability filter.");
      }
      sql.append("""
           AND CASE
             WHEN pending_cancellation.id IS NOT NULL OR pending_merge.plot_id IS NOT NULL THEN 'COMMITTED'
             WHEN sm.source_plot_id IS NOT NULL THEN 'AVAILABLE'
             WHEN sb.sale_booking_id IS NOT NULL AND UPPER(COALESCE(sb.sale_status, '')) NOT IN ('CANCELLED','MERGED') THEN 'COMMITTED'
             WHEN UPPER(COALESCE(sb.sale_status, '')) = 'CANCELLED'
                  AND UPPER(p.status) IN ('AVAILABLE','UNALLOCATED','OPEN') THEN 'AVAILABLE'
             WHEN tr.id IS NOT NULL THEN 'COMMITTED'
             WHEN UPPER(p.status) IN ('AVAILABLE','UNALLOCATED','OPEN') THEN 'AVAILABLE'
             ELSE 'UNAVAILABLE'
           END = ?
          """);
      params.add(availability.trim().toUpperCase(Locale.ROOT));
    }
    sql.append(" ORDER BY b.block_code, p.plot_no");
    return jdbc.queryForList(sql.toString(), params.toArray());
  }

  public List<Map<String, Object>> blocks() {
    return jdbc.queryForList("""
        SELECT b.block_id AS id, b.block_code AS blockCode, b.block_name AS blockName,
               b.project_id AS projectId
        FROM blocks b WHERE b.is_active = 1 ORDER BY b.block_code, b.block_name
        """);
  }

  @Transactional
  public Map<String, Object> create(PlotInventoryRequest request) {
    validateRequest(request);
    lockNumberSequence();
    ensureActiveBlock(request.blockId());

    String plotNo = nextPlotNumber();
    try {
      jdbc.update("""
          INSERT INTO plots(block_id, plot_no, plot_size_marla, plot_type, category, status)
          VALUES (?, ?, ?, ?, ?, 'AVAILABLE')
          """, request.blockId(), plotNo, request.sizeMarla(), normalizeType(request.type()),
          blankToNull(request.category()));
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The generated plot number conflicts with an existing plot. Refresh and retry.");
    }

    Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("plotNo", plotNo);
    result.put("blockId", request.blockId());
    result.put("sizeMarla", request.sizeMarla());
    result.put("type", normalizeType(request.type()));
    result.put("category", blankToNull(request.category()));
    result.put("status", "AVAILABLE");
    result.put("availability", "AVAILABLE");
    return result;
  }

  @Transactional
  public Map<String, Object> update(Long plotId, PlotInventoryRequest request) {
    validateRequest(request);
    Map<String, Object> current = loadForUpdate(plotId);
    String status = normalizeStatus(request.status());
    ensureActiveBlock(request.blockId());

    if (AVAILABLE_STATUSES.contains(status)) {
      ensureNotCommittedOrMerged(plotId);
    }
    if (((Number) current.get("block_id")).longValue() != request.blockId()) {
      Integer linked = jdbc.queryForObject("""
          SELECT COUNT(*) FROM sales_bookings WHERE plot_id = ?
          """, Integer.class, plotId);
      Integer merged = jdbc.queryForObject("""
          SELECT COUNT(*) FROM webv9_plot_merges
          WHERE (source_plot_id = ? OR target_plot_id = ?) AND UPPER(status) = 'APPROVED'
          """, Integer.class, plotId, plotId);
      if ((linked != null && linked > 0) || (merged != null && merged > 0)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "A plot with booking or merge history cannot be moved to another block.");
      }
      Integer duplicate = jdbc.queryForObject("""
          SELECT COUNT(*) FROM plots WHERE block_id = ? AND plot_no = ? AND plot_id <> ?
          """, Integer.class, request.blockId(), current.get("plot_no"), plotId);
      if (duplicate != null && duplicate > 0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "This plot number already exists in the selected block.");
      }
    }

    try {
      jdbc.update("""
          UPDATE plots
          SET block_id = ?, plot_size_marla = ?, plot_type = ?, category = ?, status = ?
          WHERE plot_id = ?
          """, request.blockId(), request.sizeMarla(), normalizeType(request.type()),
          blankToNull(request.category()), status, plotId);
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This plot number already exists in the selected block.");
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", plotId);
    result.put("plotNo", current.get("plot_no"));
    result.put("blockId", request.blockId());
    result.put("sizeMarla", request.sizeMarla());
    result.put("type", normalizeType(request.type()));
    result.put("category", blankToNull(request.category()));
    result.put("plotStatus", status);
    result.put("availability", AVAILABLE_STATUSES.contains(status) ? "AVAILABLE" : "UNAVAILABLE");
    return result;
  }

  private String nextPlotNumber() {
    Integer highest = jdbc.queryForObject("""
        SELECT COALESCE(MAX(CAST(SUBSTRING(plot_no, 5) AS UNSIGNED)), 0)
        FROM plots WHERE plot_no REGEXP '^DOL-[0-9]{5}$'
        """, Integer.class);
    int next = (highest == null ? 0 : highest) + 1;
    while (next <= 99_999) {
      String candidate = String.format(Locale.ROOT, "DOL-%05d", next);
      Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM plots WHERE plot_no = ?", Integer.class, candidate);
      if (exists == null || exists == 0) return candidate;
      next++;
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, "No five-digit District One plot numbers remain.");
  }

  private void lockNumberSequence() {
    try {
      jdbc.queryForObject("SELECT block_id FROM blocks ORDER BY block_id LIMIT 1 FOR UPDATE", Long.class);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Create an active block before adding plots.");
    }
  }

  private Map<String, Object> loadForUpdate(Long plotId) {
    try {
      return jdbc.queryForMap("SELECT plot_id, block_id, plot_no FROM plots WHERE plot_id = ? FOR UPDATE", plotId);
    } catch (EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plot not found.");
    }
  }

  private void ensureActiveBlock(Long blockId) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM blocks WHERE block_id = ? AND is_active = 1", Integer.class, blockId);
    if (count == null || count == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select an active block.");
    }
  }

  private void ensureNotCommittedOrMerged(Long plotId) {
    Integer committed = jdbc.queryForObject("""
        SELECT COUNT(*) FROM sales_bookings sb
        WHERE sb.plot_id = ?
          AND sb.sale_booking_id = (SELECT MAX(latest.sale_booking_id) FROM sales_bookings latest WHERE latest.plot_id = ?)
          AND UPPER(COALESCE(sb.sale_status, '')) NOT IN ('CANCELLED','MERGED')
        """, Integer.class, plotId, plotId);
    if (committed != null && committed > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "A committed or merged plot cannot be made available.");
    }
  }

  private void validateRequest(PlotInventoryRequest request) {
    if (request == null || request.blockId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Block is required.");
    }
    normalizeType(request.type());
    normalizeStatus(request.status());
    if (request.category() != null && request.category().length() > 100) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category must be 100 characters or fewer.");
    }
  }

  private String normalizeType(String type) {
    if (type == null || type.isBlank()) return null;
    return switch (type.trim().toUpperCase(Locale.ROOT)) {
      case "RESIDENTIAL" -> "Residential";
      case "COMMERCIAL" -> "Commercial";
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plot type must be Residential or Commercial.");
    };
  }

  private String normalizeStatus(String status) {
    if (status == null || status.isBlank()) return "AVAILABLE";
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!VALID_STATUSES.contains(normalized)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported plot status.");
    }
    return normalized;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
