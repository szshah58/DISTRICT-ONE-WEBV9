package com.districtone.webv9.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class SalesWorkflowRepository {
  private final JdbcTemplate jdbc;

  public SalesWorkflowRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Map<String, Object> lockMerge(Long id) {
    return jdbc.queryForMap("SELECT * FROM webv9_plot_merges WHERE id = ? FOR UPDATE", id);
  }

  public Map<String, Object> lockCancellation(Long id) {
    return jdbc.queryForMap("SELECT * FROM webv9_cancellations WHERE id = ? FOR UPDATE", id);
  }

  public List<Map<String, Object>> mergeEvents(Long id) {
    return jdbc.queryForList("""
        SELECT id, request_version AS requestVersion, event_type AS eventType,
               from_status AS fromStatus, to_status AS toStatus,
               actor_user_id AS actorUserId, target_user_id AS targetUserId,
               remarks, request_snapshot AS requestSnapshot, created_at AS createdAt
        FROM webv9_sales_request_events WHERE merge_id = ? ORDER BY id
        """, id);
  }

  public List<Map<String, Object>> cancellationEvents(Long id) {
    return jdbc.queryForList("""
        SELECT id, request_version AS requestVersion, event_type AS eventType,
               from_status AS fromStatus, to_status AS toStatus,
               actor_user_id AS actorUserId, target_user_id AS targetUserId,
               remarks, request_snapshot AS requestSnapshot, created_at AS createdAt
        FROM webv9_sales_request_events WHERE cancellation_id = ? ORDER BY id
        """, id);
  }

  public List<Map<String, Object>> mergeTransfers(Long id) {
    return jdbc.queryForList("""
        SELECT h.id AS transferId, h.total_amount AS totalAmount, h.created_at AS createdAt,
               h.source_booking_id AS sourceBookingId, h.target_booking_id AS targetBookingId,
               h.source_plot_no AS sourcePlotNo, h.target_plot_no AS targetPlotNo,
               l.payment_id AS paymentId, l.allocation_id AS allocationId,
               l.application_id AS applicationId, l.source_receivable_id AS sourceReceivableId,
               l.target_receivable_id AS targetReceivableId, l.amount
        FROM webv9_sales_merge_transfers h
        LEFT JOIN webv9_sales_merge_transfer_lines l ON l.transfer_id = h.id
        WHERE h.merge_id = ? ORDER BY l.id
        """, id);
  }

  public List<Map<String, Object>> cancellationLines(Long id) {
    return jdbc.queryForList("""
        SELECT id, finance_adjustment_id AS financeAdjustmentId,
               receivable_id AS receivableId, amount, description, created_by AS createdBy, created_at AS createdAt
        FROM webv9_sales_cancellation_settlement_lines WHERE cancellation_id = ? ORDER BY id
        """, id);
  }

  public Map<String, Object> refund(Long id) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT id, member_id AS memberId, posted_amount AS postedAmount,
               deduction_amount AS deductionAmount, refund_amount AS refundAmount,
               status, payout_reference AS payoutReference, approved_by AS approvedBy,
               approved_at AS approvedAt, completed_by AS completedBy,
               completed_at AS completedAt, remarks
        FROM webv9_sales_refund_disbursements WHERE cancellation_id = ?
        """, id);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }
}
