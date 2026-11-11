package com.districtone.webv9.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.districtone.webv9.security.ProjectAccessService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregate feed for the WEBV9 dashboard. Every figure is derived from the
 * existing District One tables so the dashboard never needs a second source of truth.
 * Monetary series are returned gap-filled so charts render continuous periods.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
  /** Number of trailing calendar months reported for every time series. */
  private static final int TREND_MONTHS = 12;
  private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyy-MM");
  private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yy");
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** Outstanding balance on a receivable line: billed plus surcharge less credits and payments. */
  private static final String OUTSTANDING = """
      (COALESCE(original_amount, 0) + COALESCE(surcharge_amount, 0)
       - COALESCE(paid_amount, 0) - COALESCE(adjustment_amount, 0))
      """;

  /** Legacy ledger rows are used only when that registration has not moved to WEBV9 Finance. */
  private static final String LEGACY_FALLBACK = """
      s.validation_status = 'MATCHED'
      AND s.matched_registration_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM webv9_receivables vr
                      WHERE vr.registration_id = s.matched_registration_id)
      AND NOT EXISTS (SELECT 1 FROM webv9_payment_allocations a
                      JOIN payments_receipts pr ON pr.id = a.payment_id
                      WHERE a.registration_id = s.matched_registration_id
                        AND pr.status IN ('VERIFIED', 'POSTED'))
      """;

  /** Receipts already represented by the matched legacy ledger must not be counted again. */
  private static final String NOT_IN_LEGACY_LEDGER = """
      NOT EXISTS (
        SELECT 1 FROM registrations r
        JOIN recovery_staging s ON s.matched_registration_id = r.registration_id
        WHERE r.member_id = pr.customer_id AND %s
      )
      """.formatted(LEGACY_FALLBACK);

  /**
   * Real recovery positions staged from the District One ledger import, keyed by the month
   * each instalment fell due: what was billed, what was actually recovered against it, and
   * the balance still owing on the very same lines.
   */
  private static final String RECOVERY_LINES = """
      SELECT DATE_FORMAT(due_date, '%%Y-%%m') AS month,
             COUNT(*) AS line_count,
             COALESCE(SUM(COALESCE(due_amount, 0) + COALESCE(sur_amount, 0)), 0) AS billed,
             COALESCE(SUM(CASE WHEN source_posted_flag = 1 THEN COALESCE(amount, 0) ELSE 0 END), 0) AS recovered,
             COALESCE(SUM(GREATEST(COALESCE(due_amount, 0) + COALESCE(sur_amount, 0)
               - CASE WHEN source_posted_flag = 1 THEN COALESCE(amount, 0) ELSE 0 END, 0)), 0) AS outstanding
      FROM recovery_staging s
      WHERE due_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
        AND due_date < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%%Y-%%m-01')
        AND %s
      GROUP BY DATE_FORMAT(due_date, '%%Y-%%m')
      """;

  /** One month of staged recovery lines, as stored by the ledger import. */
  private record RecoveryMonth(BigDecimal billed, BigDecimal recovered, BigDecimal outstanding, long lines) {}

  private final JdbcTemplate jdbc;
  private final ProjectAccessService projectAccess;
  public DashboardController(JdbcTemplate jdbc, ProjectAccessService projectAccess) {
    this.jdbc = jdbc;
    this.projectAccess = projectAccess;
  }

  @GetMapping
  public Map<String, Object> summary(Authentication authentication) {
    if (!projectAccess.isSuperAdmin(authentication.getName()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Dashboard aggregates are system-wide; a project-scoped dashboard is not available yet");
    BigDecimal legacyDue = legacyAmount("COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)");
    BigDecimal legacyPaid = legacyAmount("CASE WHEN s.source_posted_flag = 1 THEN COALESCE(s.amount, 0) ELSE 0 END");
    BigDecimal legacyDevelopmentDue = legacyDevelopmentChargeSchedule();
    BigDecimal collections = legacyPaid.add(money("""
        SELECT COALESCE(SUM(pr.amount), 0) FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED') AND %s
        """.formatted(NOT_IN_LEGACY_LEDGER)));
    BigDecimal receivables = money("SELECT COALESCE(SUM(" + OUTSTANDING + "), 0) FROM webv9_receivables")
        .add(legacyDue.subtract(legacyPaid)).add(legacyDevelopmentDue);
    BigDecimal overdue = money("""
        SELECT COALESCE(SUM(%s), 0) FROM webv9_receivables
        WHERE %s > 0 AND DATEDIFF(CURDATE(), due_date) > 0
        """.formatted(OUTSTANDING, OUTSTANDING)).add(legacyAmount("""
            CASE WHEN s.due_date < CURDATE()
                 THEN GREATEST(COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)
                      - CASE WHEN s.source_posted_flag = 1 THEN COALESCE(s.amount, 0) ELSE 0 END, 0)
                 ELSE 0 END
            """));
    YearMonth currentMonth = YearMonth.from(LocalDate.now());
    BigDecimal monthCollections = money("""
        SELECT COALESCE(SUM(pr.amount), 0) FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED') AND %s
          AND pr.payment_date >= ? AND pr.payment_date < ?
        """.formatted(NOT_IN_LEGACY_LEDGER), currentMonth.atDay(1), currentMonth.plusMonths(1).atDay(1));
    BigDecimal previousMonthCollections = money("""
        SELECT COALESCE(SUM(pr.amount), 0) FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED') AND %s
          AND pr.payment_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 MONTH), '%%Y-%%m-01')
          AND pr.payment_date < DATE_FORMAT(CURDATE(), '%%Y-%%m-01')
        """.formatted(NOT_IN_LEGACY_LEDGER));
    BigDecimal todayCollections = money("""
        SELECT COALESCE(SUM(pr.amount), 0) FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED') AND %s
          AND pr.payment_date >= CURDATE()
          AND pr.payment_date < DATE_ADD(CURDATE(), INTERVAL 1 DAY)
        """.formatted(NOT_IN_LEGACY_LEDGER));

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("members", count("SELECT COUNT(*) FROM members"));
    summary.put("plots", count("SELECT COUNT(*) FROM plots"));
    summary.put("availablePlots", count("""
        SELECT COUNT(*) FROM plots
        WHERE UPPER(status) IN ('AVAILABLE', 'UNALLOCATED', 'OPEN')
        """));
    summary.put("soldBookedPlots", count("""
        SELECT COUNT(DISTINCT plot_id) FROM sales_bookings
        WHERE plot_id IS NOT NULL AND UPPER(sale_status) IN ('ALLOCATED', 'BOOKED', 'SOLD')
        """));
    summary.put("collections", collections);
    summary.put("receivables", receivables);
    summary.put("pendingFinanceVerification", count("""
        SELECT COUNT(*) FROM payments_receipts
        WHERE status IN ('RECOVERY_ENTRY', 'PENDING', 'PENDING_VERIFICATION')
        """));

    // Headline movement figures used for the KPI trend indicators.
    summary.put("todayCollections", todayCollections);
    summary.put("monthCollections", monthCollections);
    summary.put("previousMonthCollections", previousMonthCollections);
    summary.put("overdueReceivables", overdue);
    summary.put("overdueReceivableCount", count("""
        SELECT COUNT(*) FROM webv9_receivables
        WHERE %s > 0 AND DATEDIFF(CURDATE(), due_date) > 0
        """.formatted(OUTSTANDING)) + count("""
        SELECT COUNT(*) FROM recovery_staging s
        WHERE %s AND s.due_date < CURDATE()
          AND COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)
              - CASE WHEN s.source_posted_flag = 1 THEN COALESCE(s.amount, 0) ELSE 0 END > 0
        """.formatted(LEGACY_FALLBACK)));
    summary.put("collectionRate", ratio(collections, collections.add(receivables)));
    summary.put("surcharges", money("SELECT COALESCE(SUM(surcharge_amount), 0) FROM webv9_receivables")
        .add(legacyAmount("COALESCE(s.sur_amount, 0)")));
    summary.put("averageReceipt", averageReceipt());
    summary.put("openCareCases", count("""
        SELECT COUNT(*) FROM webv9_customer_care_cases WHERE status NOT IN ('RESOLVED', 'CLOSED')
        """));

    // Financial series.
    summary.put("receivablesAging", receivablesAging());
    summary.put("receivableStatusMix", receivableStatusMix());
    summary.put("paymentModes", paymentModes());
    summary.put("chargeRecovery", chargeRecovery());
    summary.put("monthlyCollections", monthlyCollections());
    summary.put("monthlyDemand", monthlyDemand());
    summary.put("monthlyRecoveryPercentage", monthlyRecoveryPercentage());
    summary.put("financeStatuses", labelCountAmount("""
        SELECT COALESCE(NULLIF(status, ''), 'Unknown') AS label, amount
        FROM payments_receipts
        """));

    // Operational series.
    summary.put("monthlySalesBookings", monthlyBookings());
    summary.put("monthlyMembers", monthlyMembers());
    summary.put("projectInventory", projectInventory());
    summary.put("blockOccupancy", blockOccupancy());
    summary.put("plotStatuses", labelCount("""
        SELECT COALESCE(NULLIF(status, ''), 'Unknown') AS label FROM plots
        """));
    summary.put("bookingPipeline", labelCount("""
        SELECT COALESCE(NULLIF(sale_status, ''), 'Unknown') AS label FROM sales_bookings
        """));
    summary.put("carePipeline", labelCount("""
        SELECT COALESCE(NULLIF(status, ''), 'Unknown') AS label FROM webv9_customer_care_cases
        """));
    summary.put("activityByModule", labelCount("""
        SELECT COALESCE(NULLIF(module, ''), 'System') AS label FROM system_logs
        WHERE occurred_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
        """));

    summary.put("recentActivity", jdbc.queryForList("""
        SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:%i:%s') AS occurredAt,
               COALESCE(action, '') AS action, COALESCE(module, '') AS module
        FROM system_logs ORDER BY occurred_at DESC LIMIT 8
        """));
    summary.put("trendMonths", TREND_MONTHS);
    return summary;
  }

  /** Receivables still owing, split into standard ageing buckets ordered oldest last. */
  private List<Map<String, Object>> receivablesAging() {
    return jdbc.queryForList("""
        SELECT CASE bucket_order
                 WHEN 0 THEN 'Not yet due'
                 WHEN 1 THEN '1-30 days'
                 WHEN 2 THEN '31-60 days'
                 WHEN 3 THEN '61-90 days'
                 ELSE 'Over 90 days'
               END AS label,
               COUNT(*) AS count,
               COALESCE(SUM(outstanding), 0) AS amount
        FROM (
          SELECT CASE
                   WHEN DATEDIFF(CURDATE(), due_date) <= 0 THEN 0
                   WHEN DATEDIFF(CURDATE(), due_date) <= 30 THEN 1
                   WHEN DATEDIFF(CURDATE(), due_date) <= 60 THEN 2
                   WHEN DATEDIFF(CURDATE(), due_date) <= 90 THEN 3
                   ELSE 4
                 END AS bucket_order,
                 outstanding
          FROM (
            SELECT due_date, %s AS outstanding FROM webv9_receivables
            UNION ALL
            SELECT s.due_date,
                   GREATEST(COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)
                     - CASE WHEN s.source_posted_flag = 1 THEN COALESCE(s.amount, 0) ELSE 0 END, 0)
            FROM recovery_staging s WHERE %s
          ) balances
        ) x
        WHERE x.outstanding > 0
        GROUP BY bucket_order
        ORDER BY bucket_order
        """.formatted(OUTSTANDING, LEGACY_FALLBACK));
  }

  /** Receivable lines by lifecycle status with the balance attached to each status. */
  private List<Map<String, Object>> receivableStatusMix() {
    return jdbc.queryForList("""
        SELECT COALESCE(NULLIF(status, ''), 'Unknown') AS label,
               COUNT(*) AS count,
               COALESCE(SUM(%s), 0) AS amount
        FROM webv9_receivables
        GROUP BY status
        ORDER BY count DESC, label
        """.formatted(OUTSTANDING));
  }

  /** Posted collection value by the mode the money actually arrived through. */
  private List<Map<String, Object>> paymentModes() {
    return jdbc.queryForList("""
        SELECT CASE WHEN UPPER(TRIM(pr.payment_method)) = 'CASH' THEN 'CASH'
                    WHEN UPPER(TRIM(pr.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE') THEN 'BANK'
                    ELSE COALESCE(NULLIF(pr.payment_method, ''), 'Unspecified') END AS label,
               COUNT(*) AS count,
               COALESCE(SUM(pr.amount), 0) AS amount
        FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED')
        GROUP BY CASE WHEN UPPER(TRIM(pr.payment_method)) = 'CASH' THEN 'CASH'
                      WHEN UPPER(TRIM(pr.payment_method)) IN ('BANK','BANK_TRANSFER','ONLINE','ONLINE_TRANSFER','CHEQUE') THEN 'BANK'
                      ELSE COALESCE(NULLIF(pr.payment_method, ''), 'Unspecified') END
        ORDER BY amount DESC, label
        """);
  }

  private BigDecimal legacyAmount(String amountExpression) {
    return money("SELECT COALESCE(SUM(" + amountExpression + "), 0) "
        + "FROM recovery_staging s WHERE " + LEGACY_FALLBACK);
  }

  /** May 2026 Development Charges installment, added only if no DC debit exists for the registration. */
  private BigDecimal legacyDevelopmentChargeSchedule() {
    return money("""
        SELECT COALESCE(SUM(d.installment_may_2026), 0)
        FROM development_charge_accounts d
        WHERE NOT EXISTS (
          SELECT 1 FROM webv9_receivables vr
          LEFT JOIN webv9_charge_types ct ON ct.id = vr.charge_type_id
          WHERE vr.registration_id = d.registration_id
            AND (LOWER(CONCAT(COALESCE(ct.code, ''), ' ', COALESCE(ct.name, ''), ' ', COALESCE(vr.description, ''))) LIKE '%%development%%'
              OR LOWER(CONCAT(COALESCE(ct.code, ''), ' ', COALESCE(ct.name, ''), ' ', COALESCE(vr.description, ''))) REGEXP '(^|[^a-z0-9])(dc|dca)([^a-z0-9]|$)')
        )
        AND NOT EXISTS (
          SELECT 1 FROM recovery_staging s
          WHERE s.matched_registration_id = d.registration_id AND %s
            AND (EXISTS (SELECT 1 FROM webv9_charge_types ct
                         WHERE ct.code = 'DEVELOPMENT' AND ct.id = CAST(s.acc_head AS UNSIGNED))
              OR LOWER(CONCAT(COALESCE(s.acc_head, ''), ' ', COALESCE(s.acc_desc, ''), ' ', COALESCE(s.tran_desc, ''))) LIKE '%%development%%'
              OR LOWER(CONCAT(COALESCE(s.acc_head, ''), ' ', COALESCE(s.acc_desc, ''), ' ', COALESCE(s.tran_desc, ''))) REGEXP '(^|[^a-z0-9])(dc|dca)([^a-z0-9]|$)')
        )
        """.formatted(LEGACY_FALLBACK));
  }

  /** Outstanding balance grouped by the charge head it was raised under. */
  private List<Map<String, Object>> chargeRecovery() {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT COALESCE(ct.name, 'Unassigned charge') AS label,
               COUNT(*) AS count,
               COALESCE(SUM(x.outstanding), 0) AS amount
        FROM (
          SELECT r.charge_type_id, %s AS outstanding
          FROM webv9_receivables r
          UNION ALL
          SELECT CAST(s.acc_head AS UNSIGNED) AS charge_type_id,
                 GREATEST(COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)
                   - CASE WHEN s.source_posted_flag = 1 THEN COALESCE(s.amount, 0) ELSE 0 END, 0) AS outstanding
          FROM recovery_staging s WHERE %s
        ) x
        LEFT JOIN webv9_charge_types ct ON ct.id = x.charge_type_id
        WHERE x.outstanding > 0
        GROUP BY COALESCE(ct.name, 'Unassigned charge')
        ORDER BY amount DESC
        """.formatted(OUTSTANDING, LEGACY_FALLBACK));
    BigDecimal scheduledDevelopmentCharges = legacyDevelopmentChargeSchedule();
    if (scheduledDevelopmentCharges.signum() > 0) {
      Map<String, Object> development = rows.stream()
          .filter(row -> "Development Charges".equalsIgnoreCase(String.valueOf(row.get("label"))))
          .findFirst().orElse(null);
      if (development == null) {
        rows.add(Map.of("label", "Development Charges", "count", 0, "amount", scheduledDevelopmentCharges));
      } else {
        BigDecimal current = new BigDecimal(String.valueOf(development.get("amount")));
        development.put("amount", current.add(scheduledDevelopmentCharges));
      }
    }
    return rows;
  }

  /** Posted receipts per posting month, with a running collection total. */
  private List<Map<String, Object>> monthlyCollections() {
    Map<String, double[]> totals = new LinkedHashMap<>();
    jdbc.query("""
        SELECT DATE_FORMAT(pr.payment_date, '%%Y-%%m') AS month,
               COUNT(*) AS receipts,
               COALESCE(SUM(pr.amount), 0) AS amount
        FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED') AND %s
          AND pr.payment_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
          AND pr.payment_date < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%%Y-%%m-01')
        GROUP BY DATE_FORMAT(pr.payment_date, '%%Y-%%m')
        """.formatted(NOT_IN_LEGACY_LEDGER, TREND_MONTHS - 1), (RowCallbackHandler) rs -> totals.put(rs.getString("month"),
        new double[] {rs.getDouble("amount"), rs.getDouble("receipts")}));

    List<Map<String, Object>> series = new ArrayList<>();
    BigDecimal running = BigDecimal.ZERO;
    for (YearMonth month : trendMonths()) {
      String key = month.format(MONTH_KEY);
      double[] found = totals.getOrDefault(key, new double[] {0d, 0d});
      BigDecimal amount = BigDecimal.valueOf(found[0]).setScale(2, RoundingMode.HALF_UP);
      running = running.add(amount);
      Map<String, Object> row = monthRow(month);
      row.put("amount", amount);
      row.put("receipts", (long) found[1]);
      row.put("cumulative", running);
      series.add(row);
    }
    return series;
  }

  /**
   * Instalment demand falling due per month against money actually collected in that
   * month, so recovery performance is visible rather than only the cash total.
   */
  private List<Map<String, Object>> monthlyDemand() {
    Map<String, BigDecimal> scheduled = new LinkedHashMap<>();
    jdbc.query("""
        SELECT DATE_FORMAT(due_date, '%%Y-%%m') AS month, COALESCE(SUM(amount), 0) AS amount
        FROM webv9_instalment_schedules
        WHERE due_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
          AND due_date < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%%Y-%%m-01')
        GROUP BY DATE_FORMAT(due_date, '%%Y-%%m')
        """.formatted(TREND_MONTHS - 1), (RowCallbackHandler) rs -> scheduled.put(rs.getString("month"), rs.getBigDecimal("amount")));

    jdbc.query("""
        SELECT DATE_FORMAT(s.due_date, '%%Y-%%m') AS month,
               COALESCE(SUM(COALESCE(s.due_amount, 0) + COALESCE(s.sur_amount, 0)), 0) AS amount
        FROM recovery_staging s
        WHERE %s
          AND s.due_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
          AND s.due_date < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%%Y-%%m-01')
        GROUP BY DATE_FORMAT(s.due_date, '%%Y-%%m')
        """.formatted(LEGACY_FALLBACK, TREND_MONTHS - 1), (RowCallbackHandler) rs ->
        scheduled.merge(rs.getString("month"), rs.getBigDecimal("amount"), BigDecimal::add));

    Map<String, BigDecimal> collected = new LinkedHashMap<>();
    jdbc.query("""
        SELECT DATE_FORMAT(pr.payment_date, '%%Y-%%m') AS month, COALESCE(SUM(pr.amount), 0) AS amount
        FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED') AND %s
          AND pr.payment_date >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
          AND pr.payment_date < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 MONTH), '%%Y-%%m-01')
        GROUP BY DATE_FORMAT(pr.payment_date, '%%Y-%%m')
        """.formatted(NOT_IN_LEGACY_LEDGER, TREND_MONTHS - 1), (RowCallbackHandler) rs -> collected.put(rs.getString("month"), rs.getBigDecimal("amount")));

    List<Map<String, Object>> series = new ArrayList<>();
    for (YearMonth month : trendMonths()) {
      String key = month.format(MONTH_KEY);
      BigDecimal due = zeroIfNull(scheduled.get(key));
      BigDecimal paid = zeroIfNull(collected.get(key));
      Map<String, Object> row = monthRow(month);
      row.put("scheduled", due);
      row.put("collected", paid);
      row.put("variance", paid.subtract(due));
      series.add(row);
    }
    return series;
  }

  /**
   * Recovery percentage per month: the amount actually recovered for the month divided by
   * the amount still outstanding on that same month's instalment lines, times one hundred.
   * A month with nothing owing has no ratio to report, so it is sent as null rather than a
   * misleading zero.
   */
  private List<Map<String, Object>> monthlyRecoveryPercentage() {
    Map<String, RecoveryMonth> months = new LinkedHashMap<>();
    jdbc.query(RECOVERY_LINES.formatted(TREND_MONTHS - 1, LEGACY_FALLBACK), (RowCallbackHandler) rs -> months.put(rs.getString("month"),
        new RecoveryMonth(rs.getBigDecimal("billed"), rs.getBigDecimal("recovered"),
            rs.getBigDecimal("outstanding"), rs.getLong("line_count"))));

    List<Map<String, Object>> series = new ArrayList<>();
    for (YearMonth month : trendMonths()) {
      RecoveryMonth found = months.get(month.format(MONTH_KEY));
      BigDecimal billed = found == null ? BigDecimal.ZERO : zeroIfNull(found.billed());
      BigDecimal recovered = found == null ? BigDecimal.ZERO : zeroIfNull(found.recovered());
      BigDecimal outstanding = found == null ? BigDecimal.ZERO : zeroIfNull(found.outstanding());
      Map<String, Object> row = monthRow(month);
      row.put("billed", billed);
      row.put("recovered", recovered);
      row.put("outstanding", outstanding);
      row.put("lineCount", found == null ? 0L : found.lines());
      row.put("recoveryPercent", outstanding.signum() > 0 ? ratio(recovered, outstanding) : null);
      series.add(row);
    }
    return series;
  }

  /** New sales bookings created per month. */
  private List<Map<String, Object>> monthlyBookings() {
    Map<String, Long> counts = new LinkedHashMap<>();
    jdbc.query("""
        SELECT DATE_FORMAT(created_at, '%%Y-%%m') AS month, COUNT(*) AS total
        FROM sales_bookings
        WHERE created_at >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
        GROUP BY DATE_FORMAT(created_at, '%%Y-%%m')
        """.formatted(TREND_MONTHS - 1), (RowCallbackHandler) rs -> counts.put(rs.getString("month"), rs.getLong("total")));

    List<Map<String, Object>> series = new ArrayList<>();
    for (YearMonth month : trendMonths()) {
      Map<String, Object> row = monthRow(month);
      row.put("count", counts.getOrDefault(month.format(MONTH_KEY), 0L));
      series.add(row);
    }
    return series;
  }

  /** Members registered per month, showing intake growth. */
  private List<Map<String, Object>> monthlyMembers() {
    Map<String, Long> counts = new LinkedHashMap<>();
    jdbc.query("""
        SELECT DATE_FORMAT(created_at, '%%Y-%%m') AS month, COUNT(*) AS total
        FROM members
        WHERE created_at >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL %d MONTH), '%%Y-%%m-01')
        GROUP BY DATE_FORMAT(created_at, '%%Y-%%m')
        """.formatted(TREND_MONTHS - 1), (RowCallbackHandler) rs -> counts.put(rs.getString("month"), rs.getLong("total")));

    List<Map<String, Object>> series = new ArrayList<>();
    for (YearMonth month : trendMonths()) {
      Map<String, Object> row = monthRow(month);
      row.put("count", counts.getOrDefault(month.format(MONTH_KEY), 0L));
      series.add(row);
    }
    return series;
  }

  /** Plot inventory availability versus allocation for every project. */
  private List<Map<String, Object>> projectInventory() {
    return jdbc.queryForList("""
        SELECT COALESCE(pr.project_name, 'Unassigned inventory') AS label,
               COUNT(*) AS total,
               SUM(CASE WHEN UPPER(p.status) IN ('AVAILABLE', 'UNALLOCATED', 'OPEN') THEN 1 ELSE 0 END) AS available,
               SUM(CASE WHEN UPPER(p.status) IN ('ALLOCATED', 'BOOKED', 'SOLD') THEN 1 ELSE 0 END) AS allocated
        FROM plots p
        JOIN blocks b ON b.block_id = p.block_id
        LEFT JOIN webv9_projects pr ON pr.id = b.project_id
        GROUP BY COALESCE(pr.project_name, 'Unassigned inventory')
        ORDER BY total DESC, label
        """);
  }

  /** Plot availability for the largest blocks, used for inventory pressure monitoring. */
  private List<Map<String, Object>> blockOccupancy() {
    return jdbc.queryForList("""
        SELECT b.block_name AS label,
               COUNT(*) AS total,
               SUM(CASE WHEN UPPER(p.status) IN ('AVAILABLE', 'UNALLOCATED', 'OPEN') THEN 1 ELSE 0 END) AS available,
               SUM(CASE WHEN UPPER(p.status) IN ('ALLOCATED', 'BOOKED', 'SOLD') THEN 1 ELSE 0 END) AS allocated
        FROM plots p
        JOIN blocks b ON b.block_id = p.block_id
        GROUP BY b.block_name
        ORDER BY total DESC, b.block_name
        LIMIT 10
        """);
  }

  /** Grouped row counts for any single-column text field. */
  private List<Map<String, Object>> labelCount(String sourceSql) {
    return jdbc.queryForList("SELECT label, COUNT(*) AS count FROM (" + sourceSql + ") s GROUP BY label ORDER BY count DESC, label");
  }

  /** Grouped row counts with a matching value total, for status style breakdowns. */
  private List<Map<String, Object>> labelCountAmount(String sourceSql) {
    return jdbc.queryForList("""
        SELECT label, COUNT(*) AS count, COALESCE(SUM(amount), 0) AS amount
        FROM (%s) s GROUP BY label ORDER BY count DESC, label
        """.formatted(sourceSql));
  }

  /** Mean value of a posted receipt, or zero when nothing has been posted. */
  private BigDecimal averageReceipt() {
    Double average = jdbc.queryForObject("""
        SELECT COALESCE(AVG(pr.amount), 0) FROM payments_receipts pr
        WHERE pr.status IN ('VERIFIED', 'POSTED')
        """, Double.class);
    return average == null ? BigDecimal.ZERO
        : BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP);
  }

  /** Trailing month windows, oldest first, used as the spine for every time series. */
  private List<YearMonth> trendMonths() {
    YearMonth current = YearMonth.from(LocalDate.now());
    List<YearMonth> months = new ArrayList<>(TREND_MONTHS);
    for (int offset = TREND_MONTHS - 1; offset >= 0; offset--) months.add(current.minusMonths(offset));
    return months;
  }

  private Map<String, Object> monthRow(YearMonth month) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("month", month.format(MONTH_KEY));
    row.put("label", month.format(MONTH_LABEL));
    return row;
  }

  /** Percentage of the combined collected and owing balance that has been collected. */
  private BigDecimal ratio(BigDecimal part, BigDecimal whole) {
    if (whole == null || whole.signum() == 0) return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
    return part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
  }

  private BigDecimal money(String sql, Object... args) {
    BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal zeroIfNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private Long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }
}




