package com.districtone.webv9.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/** Read-only statement assembly from installed WEBV9 finance relationships. */
@Service
public class CustomerLedgerReportService {
  private final JdbcTemplate jdbc;

  public CustomerLedgerReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Map<String, Object> generate(CustomerLedgerReportRequest request) {
    if (request == null || request.memberId() == null || request.toDate() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member and To Date are required.");
    }
    Map<String, Boolean> options = request.options() == null ? Map.of() : request.options();
    boolean hideContact = enabled(options, "hideAddressMobile");
    boolean hideReceipts = enabled(options, "hideReceipt");
    Map<String, Object> customer = customer(request.memberId(), hideContact);
    List<Map<String, Object>> sales = loadSales(request.memberId(), request.bookingId());
    List<String> warnings = new ArrayList<>();
    if ("KNOCKED_WISE".equalsIgnoreCase(request.reportType())) warnings.add("Knocked Wise has no established WEBV9 data rule; Regular grouping is shown.");
    warnIfSelected(warnings, options, "completeTransactions", "Complete Transactions has no established WEBV9 definition and was not applied.");
    warnIfSelected(warnings, options, "clearedTranOnly", "Cleared Tran Only has no established WEBV9 definition and was not applied.");
    warnIfSelected(warnings, options, "onlyActiveSale", "Sale active-status rules are not established; no sale status filter was applied.");
    warnIfSelected(warnings, options, "hideLps", "LPS is not established as a separate WEBV9 field; surcharge values remain visible.");
    warnIfSelected(warnings, options, "hidePartner", "No partner relationship is defined in the installed report data model; no partner fields are shown.");
    if (enabled(options, "onlyToDateTran")) warnings.add("To Date already limits displayed transactions; this legacy option adds no separate filter.");
    warnings.add("To Date limits receivables by due date, posted receipts by their stored payment date, and ledger entries by ledger transaction time. The schema has no historical receivable or surcharge snapshots; balances use current stored receivable amounts and are not a reconstructed past balance.");
    warnings.add("Booking factor and cost of land are not columns in the installed booking, registration or plot schema; those values are not fabricated.");
    warnings.add("Posted receipts with no allocation to a WEBV9 sale are listed separately at customer level when receipt details are shown.");

    List<Long> bookingIds = sales.stream().map(s -> ((Number) s.get("bookingId")).longValue()).toList();
    Timestamp before = Timestamp.valueOf(request.toDate().plusDays(1).atStartOfDay());
    Map<Long, List<Map<String, Object>>> receivables = loadReceivables(bookingIds, request.toDate());
    Map<Long, List<Map<String, Object>>> ledger = loadLedger(request.memberId(), bookingIds, before);
    Map<Long, List<Map<String, Object>>> receipts = hideReceipts ? Map.of() : loadAllocatedReceipts(request.memberId(), bookingIds, request.toDate());
    List<Map<String, Object>> unallocated = hideReceipts ? List.of() : loadUnallocatedReceipts(request.memberId(), request.toDate());

    BigDecimal totalCharges = BigDecimal.ZERO, totalPaid = BigDecimal.ZERO, totalAdjustments = BigDecimal.ZERO;
    BigDecimal totalSurcharge = BigDecimal.ZERO, totalOutstanding = BigDecimal.ZERO;
    List<Map<String, Object>> saleSections = new ArrayList<>();
    for (Map<String, Object> sale : sales) {
      Long id = ((Number) sale.get("bookingId")).longValue();
      List<Map<String, Object>> items = receivables.getOrDefault(id, List.of());
      BigDecimal charges = sum(items, "amount"), paid = sum(items, "paidAmount");
      BigDecimal adjustments = sum(items, "adjustmentAmount"), surcharge = sum(items, "surchargeAmount");
      BigDecimal outstanding = sum(items, "outstanding");
      totalCharges = totalCharges.add(charges); totalPaid = totalPaid.add(paid);
      totalAdjustments = totalAdjustments.add(adjustments); totalSurcharge = totalSurcharge.add(surcharge);
      totalOutstanding = totalOutstanding.add(outstanding);
      Map<String, Object> section = new LinkedHashMap<>(sale);
      section.put("receivables", items);
      section.put("ledgerTransactions", ledger.getOrDefault(id, List.of()));
      section.put("receipts", receipts.getOrDefault(id, List.of()));
      section.put("totals", totals(charges, paid, adjustments, surcharge, outstanding));
      saleSections.add(section);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("company", Map.of("name", "District One Housing"));
    result.put("customer", customer);
    result.put("criteria", Map.of("memberId", request.memberId(), "bookingId", request.bookingId() == null ? "ALL" : request.bookingId(),
        "toDate", request.toDate(), "reportType", request.reportType() == null ? "REGULAR" : request.reportType()));
    result.put("sales", saleSections);
    result.put("unallocatedReceipts", unallocated);
    result.put("totals", totals(totalCharges, totalPaid, totalAdjustments, totalSurcharge, totalOutstanding));
    result.put("cutoffBasis", "RECEIVABLE_DUE_DATE_AND_LEDGER_POSTING_DATE_CURRENT_BALANCE");
    result.put("appliedOptions", Map.of("hideReceipt", hideReceipts, "hideAddressMobile", hideContact,
        "toDate", request.toDate(), "saleScope", request.bookingId() == null ? "ALL" : "ONE"));
    result.put("warnings", warnings);
    result.put("unavailableBookingFields", List.of("factor", "costOfLand"));
    return result;
  }

  private Map<String, Object> customer(Long memberId, boolean hideContact) {
    try {
      Map<String, Object> customer = normalize(jdbc.queryForMap("""
          SELECT member_id AS memberId, mbr_no AS membershipNo, name,
                 address1, address2, city, contact1, contact2, landline1, landline2
          FROM members WHERE member_id = ?
          """, memberId), "memberId", "membershipNo", "name", "address1", "address2", "city", "contact1", "contact2", "landline1", "landline2");
      List<String> registrations = jdbc.queryForList("SELECT reg_no FROM registrations WHERE member_id = ? ORDER BY registration_id", String.class, memberId);
      customer.put("registrationNumbers", registrations);
      if (hideContact) {
        customer.remove("address1"); customer.remove("address2"); customer.remove("city");
        customer.remove("contact1"); customer.remove("contact2"); customer.remove("landline1"); customer.remove("landline2");
        customer.put("contactHidden", true);
      } else customer.put("contactHidden", false);
      return customer;
    } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer/member not found.");
    }
  }

  private List<Map<String, Object>> loadSales(Long memberId, Long bookingId) {
    List<Map<String, Object>> rows;
    if (bookingId == null) rows = jdbc.queryForList(saleSql() + " WHERE r.member_id = ? ORDER BY sb.sale_booking_id", memberId);
    else rows = jdbc.queryForList(saleSql() + " WHERE r.member_id = ? AND sb.sale_booking_id = ? ORDER BY sb.sale_booking_id", memberId, bookingId);
    if (bookingId != null && rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Selected sale/booking does not belong to this customer.");
    return rows.stream().map(row -> normalize(row, "bookingId", "registrationId", "registrationNo", "bookingFormNo", "plotId", "plotNo", "blockName", "sizeMarla", "type", "category", "status", "factor", "costOfLand")).toList();
  }

  private static String saleSql() { return """
      SELECT sb.sale_booking_id AS bookingId, r.registration_id AS registrationId,
             r.reg_no AS registrationNo, r.form_no AS bookingFormNo,
             p.plot_id AS plotId, p.plot_no AS plotNo, b.block_name AS blockName,
             p.plot_size_marla AS sizeMarla, p.plot_type AS type,
             COALESCE(sb.category_snapshot, p.category, r.category) AS category,
             sb.sale_status AS status, NULL AS factor, NULL AS costOfLand
      FROM sales_bookings sb
      JOIN registrations r ON r.registration_id = sb.registration_id
      JOIN plots p ON p.plot_id = sb.plot_id
      JOIN blocks b ON b.block_id = p.block_id
      """; }

  private Map<Long, List<Map<String, Object>>> loadReceivables(List<Long> bookingIds, LocalDate toDate) {
    Map<Long, List<Map<String, Object>>> grouped = new HashMap<>();
    if (bookingIds.isEmpty()) return grouped;
    String marks = String.join(",", Collections.nCopies(bookingIds.size(), "?"));
    List<Object> args = new ArrayList<>(bookingIds); args.add(toDate);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT vr.id AS receivableId, vr.booking_id AS bookingId, vr.registration_id AS registrationId,
               vr.plot_id AS plotId, vr.charge_type_id AS chargeTypeId, ct.name AS head,
               vr.description, vr.due_date AS dueDate, vr.original_amount AS amount,
               vr.paid_amount AS paidAmount, vr.adjustment_amount AS adjustmentAmount,
               vr.surcharge_amount AS surchargeAmount,
               (vr.original_amount + vr.surcharge_amount - vr.adjustment_amount - vr.paid_amount) AS outstanding,
               vr.status
        FROM webv9_receivables vr
        LEFT JOIN webv9_charge_types ct ON ct.id = vr.charge_type_id
        WHERE vr.booking_id IN (%s) AND vr.due_date <= ?
        ORDER BY vr.booking_id, vr.due_date, vr.id
        """.formatted(marks), args.toArray());
    for (Map<String, Object> raw : rows) {
      Map<String, Object> row = normalize(raw, "receivableId", "bookingId", "registrationId", "plotId", "chargeTypeId", "head", "description", "dueDate", "amount", "paidAmount", "adjustmentAmount", "surchargeAmount", "outstanding", "status");
      grouped.computeIfAbsent(((Number) row.get("bookingId")).longValue(), k -> new ArrayList<>()).add(row);
    }
    return grouped;
  }

  private Map<Long, List<Map<String, Object>>> loadLedger(Long memberId, List<Long> bookingIds, Timestamp before) {
    Map<Long, List<Map<String, Object>>> grouped = new HashMap<>();
    if (bookingIds.isEmpty()) return grouped;
    String marks = String.join(",", Collections.nCopies(bookingIds.size(), "?"));
    List<Object> args = new ArrayList<>(); args.add(memberId); args.addAll(bookingIds); args.add(before);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT id, booking_id AS bookingId, payment_id AS paymentId, transaction_date AS transactionDate,
               transaction_type AS type, reference_no AS referenceNo, debit, credit, description
        FROM webv9_customer_ledger
        WHERE member_id = ? AND booking_id IN (%s) AND transaction_date < ?
        ORDER BY transaction_date, id
        """.formatted(marks), args.toArray());
    for (Map<String, Object> raw : rows) {
      Map<String, Object> row = normalize(raw, "id", "bookingId", "paymentId", "transactionDate", "type", "referenceNo", "debit", "credit", "description");
      grouped.computeIfAbsent(((Number) row.get("bookingId")).longValue(), k -> new ArrayList<>()).add(row);
    }
    return grouped;
  }

  private Map<Long, List<Map<String, Object>>> loadAllocatedReceipts(Long memberId, List<Long> bookingIds, LocalDate toDate) {
    Map<Long, Map<Long, Map<String, Object>>> grouped = new HashMap<>();
    if (bookingIds.isEmpty()) return Map.of();
    String marks = String.join(",", Collections.nCopies(bookingIds.size(), "?"));
    List<Object> args = new ArrayList<>(); args.add(memberId); args.addAll(bookingIds); args.add(toDate);
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT p.id AS paymentId, p.receipt_no AS receiptNo, p.payment_date AS receiptDate,
               p.payment_method AS receiptType, p.payment_reference AS paymentReference,
               p.sales_classification AS salesClassification,
               p.bank_instrument_type AS bankInstrumentType,
               p.deposit_reference AS instrumentNo, p.amount AS headerAmount,
               a.booking_id AS bookingId, a.line_no AS lineNo, a.installment_schedule_id AS scheduleId,
               sch.instalment_no AS installmentNo, sch.due_date AS installmentDueDate,
               a.id AS allocationId, pra.id AS applicationId,
               COALESCE(pra.receivable_id, a.receivable_id) AS receivableId,
               COALESCE(pra.applied_amount, a.allocated_amount) AS allocatedAmount,
               ml.id AS salesTransferId, ml.target_receivable_id AS transferredToReceivableId,
               mh.target_booking_id AS transferredToBookingId, mh.target_plot_no AS transferredToPlotNo,
               ml.amount AS transferredAmount,
               ct.name AS head, vr.description,
               COALESCE(pra.created_at, a.created_at) AS postedAt
        FROM webv9_payment_allocations a
        JOIN payments_receipts p ON p.id = a.payment_id AND p.customer_id = ? AND p.status = 'POSTED'
        LEFT JOIN webv9_payment_receivable_applications pra ON pra.allocation_id = a.id
        LEFT JOIN webv9_sales_merge_transfer_lines ml ON ml.application_id = pra.id
        LEFT JOIN webv9_sales_merge_transfers mh ON mh.id = ml.transfer_id
        LEFT JOIN webv9_receivables vr
          ON vr.id = COALESCE(pra.receivable_id, a.receivable_id) AND vr.booking_id = a.booking_id
        LEFT JOIN webv9_charge_types ct ON ct.id = COALESCE(vr.charge_type_id, a.charge_type_id)
        LEFT JOIN webv9_instalment_schedules sch ON sch.id = a.installment_schedule_id
        WHERE a.booking_id IN (%s) AND p.payment_date <= ?
        ORDER BY p.payment_date, p.id, a.booking_id, a.line_no, pra.id
        """.formatted(marks), args.toArray());
    for (Map<String, Object> raw : rows) {
      Map<String, Object> row = normalize(raw, "paymentId", "receiptNo", "receiptDate", "receiptType", "paymentReference", "salesClassification", "bankInstrumentType", "instrumentNo", "headerAmount", "bookingId", "lineNo", "scheduleId", "installmentNo", "installmentDueDate", "allocationId", "applicationId", "receivableId", "allocatedAmount", "salesTransferId", "transferredToReceivableId", "transferredToBookingId", "transferredToPlotNo", "transferredAmount", "head", "description", "postedAt");
      Long booking = ((Number) row.get("bookingId")).longValue(), payment = ((Number) row.get("paymentId")).longValue();
      Map<String, Object> receipt = grouped.computeIfAbsent(booking, k -> new LinkedHashMap<>()).computeIfAbsent(payment, k -> {
        Map<String, Object> value = new LinkedHashMap<>(row); value.put("allocatedAmount", BigDecimal.ZERO);
        value.put("allocationDetails", new ArrayList<Map<String, Object>>()); value.put("heads", new LinkedHashSet<String>());
        return value;
      });
      BigDecimal lineAmount = (BigDecimal) row.get("allocatedAmount");
      receipt.put("allocatedAmount", ((BigDecimal) receipt.get("allocatedAmount")).add(lineAmount));
      @SuppressWarnings("unchecked") List<Map<String, Object>> details = (List<Map<String, Object>>) receipt.get("allocationDetails");
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("allocationId", row.get("allocationId"));
      detail.put("applicationId", row.get("applicationId"));
      detail.put("receivableId", row.get("receivableId"));
      detail.put("amount", lineAmount);
      detail.put("scheduleId", row.get("scheduleId"));
      detail.put("installmentNo", row.get("installmentNo"));
      detail.put("dueDate", row.get("installmentDueDate"));
      detail.put("head", row.get("head"));
      detail.put("description", row.get("description"));
      detail.put("salesTransferId", row.get("salesTransferId"));
      detail.put("transferredToReceivableId", row.get("transferredToReceivableId"));
      detail.put("transferredToBookingId", row.get("transferredToBookingId"));
      detail.put("transferredToPlotNo", row.get("transferredToPlotNo"));
      detail.put("transferredAmount", row.get("transferredAmount"));
      details.add(detail);
      @SuppressWarnings("unchecked") Set<String> heads = (Set<String>) receipt.get("heads");
      if (row.get("head") != null) heads.add(String.valueOf(row.get("head")));
      receipt.put("heads", heads);
    }
    Map<Long, List<Map<String, Object>>> result = new HashMap<>();
    grouped.forEach((booking, payments) -> {
      List<Map<String, Object>> list = new ArrayList<>();
      payments.values().forEach(receipt -> { receipt.put("heads", String.join(", ", (Set<String>) receipt.get("heads"))); list.add(receipt); });
      result.put(booking, list);
    });
    return result;
  }

  private List<Map<String, Object>> loadUnallocatedReceipts(Long memberId, LocalDate toDate) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT p.id AS paymentId, p.receipt_no AS receiptNo, p.payment_date AS receiptDate,
               p.payment_method AS receiptType, p.payment_reference AS paymentReference,
               p.sales_classification AS salesClassification,
               p.bank_instrument_type AS bankInstrumentType,
               p.deposit_reference AS instrumentNo, p.amount AS receiptAmount,
               p.posted_at AS postedAt
        FROM payments_receipts p WHERE p.customer_id = ? AND p.status = 'POSTED' AND p.payment_date <= ?
          AND NOT EXISTS (
            SELECT 1 FROM webv9_payment_allocations a
            JOIN sales_bookings sb ON sb.sale_booking_id = a.booking_id
            JOIN registrations r ON r.registration_id = sb.registration_id
            WHERE a.payment_id = p.id AND r.member_id = p.customer_id
          )
        ORDER BY p.payment_date, p.id
        """, memberId, toDate);
    return rows.stream().map(row -> normalize(row, "paymentId", "receiptNo", "receiptDate", "receiptType", "paymentReference", "salesClassification", "bankInstrumentType", "instrumentNo", "receiptAmount", "postedAt")).toList();
  }

  private static Map<String, Object> totals(BigDecimal charges, BigDecimal paid, BigDecimal adjustments,
                                             BigDecimal surcharge, BigDecimal outstanding) {
    return Map.of("charges", charges, "paid", paid, "adjustments", adjustments,
        "surcharge", surcharge, "outstanding", outstanding);
  }
  private static BigDecimal sum(List<Map<String, Object>> rows, String field) {
    return rows.stream().map(row -> (BigDecimal) row.get(field)).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
  private static Map<String, Object> normalize(Map<String, Object> row, String... names) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String name : names) {
      for (Map.Entry<String, Object> entry : row.entrySet()) {
        if (name.equalsIgnoreCase(entry.getKey())) { result.put(name, entry.getValue()); break; }
      }
    }
    return result;
  }
  private static boolean enabled(Map<String, Boolean> options, String key) { return Boolean.TRUE.equals(options.get(key)); }
  private static void warnIfSelected(List<String> warnings, Map<String, Boolean> options, String key, String message) {
    if (enabled(options, key)) warnings.add(message);
  }
}
