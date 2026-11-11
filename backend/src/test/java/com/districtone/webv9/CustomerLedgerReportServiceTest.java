package com.districtone.webv9;

import com.districtone.webv9.service.CustomerLedgerReportRequest;
import com.districtone.webv9.service.CustomerLedgerReportService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerLedgerReportServiceTest {
  private JdbcTemplate jdbc;
  private CustomerLedgerReportService reports;

  @BeforeEach void setup() {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:ledger_report_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(source);
    jdbc.execute("DROP ALL OBJECTS");
    jdbc.execute("CREATE TABLE members(member_id BIGINT PRIMARY KEY,mbr_no VARCHAR(30),name VARCHAR(200),cnic_no VARCHAR(30),address1 VARCHAR(200),address2 VARCHAR(200),city VARCHAR(100),contact1 VARCHAR(50),contact2 VARCHAR(50),landline1 VARCHAR(50),landline2 VARCHAR(50))");
    jdbc.execute("CREATE TABLE registrations(registration_id BIGINT PRIMARY KEY,member_id BIGINT,reg_no VARCHAR(30),form_no VARCHAR(50),category VARCHAR(50))");
    jdbc.execute("CREATE TABLE blocks(block_id BIGINT PRIMARY KEY,block_name VARCHAR(100))");
    jdbc.execute("CREATE TABLE plots(plot_id BIGINT PRIMARY KEY,block_id BIGINT,plot_no VARCHAR(30),plot_size_marla DECIMAL(8,2),plot_type VARCHAR(50),category VARCHAR(50))");
    jdbc.execute("CREATE TABLE sales_bookings(sale_booking_id BIGINT PRIMARY KEY,registration_id BIGINT,plot_id BIGINT,sale_status VARCHAR(40),category_snapshot VARCHAR(50))");
    jdbc.execute("CREATE TABLE webv9_charge_types(id BIGINT PRIMARY KEY,name VARCHAR(100))");
    jdbc.execute("CREATE TABLE webv9_receivables(id BIGINT PRIMARY KEY,member_id BIGINT,registration_id BIGINT,booking_id BIGINT,plot_id BIGINT,charge_type_id BIGINT,due_date DATE,original_amount DECIMAL(16,2),paid_amount DECIMAL(16,2),surcharge_amount DECIMAL(16,2),adjustment_amount DECIMAL(16,2),status VARCHAR(30),description VARCHAR(500))");
    jdbc.execute("CREATE TABLE payments_receipts(id BIGINT PRIMARY KEY,customer_id BIGINT,receipt_no VARCHAR(80),payment_date DATE,amount DECIMAL(16,2),payment_method VARCHAR(30),payment_reference VARCHAR(120),sales_classification VARCHAR(20),bank_instrument_type VARCHAR(24),deposit_reference VARCHAR(120),status VARCHAR(30),posted_at TIMESTAMP)");
    jdbc.execute("CREATE TABLE webv9_payment_allocations(id BIGINT PRIMARY KEY,payment_id BIGINT,booking_id BIGINT,receivable_id BIGINT,installment_schedule_id BIGINT,line_no INT,charge_type_id BIGINT,allocated_amount DECIMAL(16,2),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    jdbc.execute("CREATE TABLE webv9_payment_receivable_applications(id BIGINT PRIMARY KEY,allocation_id BIGINT,receivable_id BIGINT,applied_amount DECIMAL(16,2),created_at TIMESTAMP)");
    jdbc.execute("CREATE TABLE webv9_sales_merge_transfers(id BIGINT PRIMARY KEY,target_booking_id BIGINT,target_plot_no VARCHAR(40))");
    jdbc.execute("CREATE TABLE webv9_sales_merge_transfer_lines(id BIGINT PRIMARY KEY,transfer_id BIGINT,application_id BIGINT,target_receivable_id BIGINT,amount DECIMAL(16,2))");
    jdbc.execute("CREATE TABLE webv9_instalment_schedules(id BIGINT PRIMARY KEY,instalment_no INT,due_date DATE)");
    jdbc.execute("CREATE TABLE webv9_customer_ledger(id BIGINT PRIMARY KEY,member_id BIGINT,booking_id BIGINT,payment_id BIGINT,transaction_date TIMESTAMP,transaction_type VARCHAR(30),reference_no VARCHAR(100),debit DECIMAL(16,2),credit DECIMAL(16,2),description VARCHAR(500))");
    jdbc.update("INSERT INTO members VALUES (1,'M-1','Customer One','CNIC-1','Street 1',NULL,'City','0300',NULL,NULL,NULL)");
    jdbc.update("INSERT INTO registrations VALUES (10,1,'REG-10','FORM-10','Residential'),(11,1,'REG-11','FORM-11','Residential')");
    jdbc.update("INSERT INTO blocks VALUES (1,'A')");
    jdbc.update("INSERT INTO plots VALUES (100,1,'A-1',5,'Residential','Standard'),(101,1,'A-2',10,'Residential','Standard')");
    jdbc.update("INSERT INTO sales_bookings VALUES (20,10,100,'ALLOCATED',NULL),(21,11,101,'CANCELLED',NULL)");
    jdbc.update("INSERT INTO webv9_charge_types VALUES (1,'Instalment'),(2,'Development Charge')");
    jdbc.update("INSERT INTO webv9_receivables VALUES (30,1,10,20,100,1,'2026-09-01',100,50,5,10,'PARTIAL','Land installment'),(31,1,11,21,101,2,'2026-09-01',100,60,0,0,'PARTIAL','Development charge')");
    jdbc.update("INSERT INTO payments_receipts VALUES (40,1,'R-40','2026-09-19',110,'BANK_TRANSFER','PAYREF-40','Sales','Cheque','INST-40','POSTED','2026-09-20 10:00:00'),(41,1,'R-41','2026-09-29',25,'CASH',NULL,NULL,NULL,NULL,'POSTED','2026-10-01 10:00:00')");
    jdbc.update("INSERT INTO webv9_payment_allocations(id,payment_id,booking_id,receivable_id,installment_schedule_id,line_no,charge_type_id,allocated_amount) VALUES (50,40,20,30,NULL,1,1,50),(51,40,21,31,NULL,2,2,60)");
    jdbc.update("INSERT INTO webv9_payment_receivable_applications VALUES (60,50,30,50,'2026-09-20 10:00:00'),(61,51,31,60,'2026-09-20 10:00:00')");
    jdbc.update("INSERT INTO webv9_customer_ledger VALUES (70,1,20,40,'2026-09-20 10:00:00','PAYMENT','R-40',0,50,'Payment received'),(71,1,21,40,'2026-09-20 10:00:00','PAYMENT','R-40',0,60,'Payment received')");
    reports = new CustomerLedgerReportService(jdbc);
  }

  @Test void allSalesKeepChargesAndMultiBookingReceiptAllocationsSeparate() {
    Map<String, Object> report = reports.generate(request(null, LocalDate.parse("2026-09-30"), Map.of()));
    List<Map<String, Object>> sales = (List<Map<String, Object>>) report.get("sales");
    assertThat(sales).hasSize(2);
    assertThat(((Number) sales.get(0).get("bookingId")).longValue()).isEqualTo(20L);
    assertThat(((Number) sales.get(1).get("bookingId")).longValue()).isEqualTo(21L);
    List<Map<String, Object>> firstReceipts = (List<Map<String, Object>>) sales.get(0).get("receipts");
    List<Map<String, Object>> secondReceipts = (List<Map<String, Object>>) sales.get(1).get("receipts");
    assertThat(firstReceipts).hasSize(1).first().satisfies(r -> assertThat((BigDecimal) r.get("allocatedAmount")).isEqualByComparingTo("50.00"));
    assertThat(secondReceipts).hasSize(1).first().satisfies(r -> assertThat((BigDecimal) r.get("allocatedAmount")).isEqualByComparingTo("60.00"));
    assertThat(firstReceipts.get(0)).containsEntry("receiptDate", java.sql.Date.valueOf("2026-09-19"));
    assertThat(firstReceipts.get(0)).containsEntry("salesClassification", "Sales")
        .containsEntry("bankInstrumentType", "Cheque").containsEntry("instrumentNo", "INST-40")
        .containsEntry("paymentReference", "PAYREF-40");
    assertThat((List<Map<String, Object>>) firstReceipts.get(0).get("allocationDetails"))
        .singleElement().satisfies(detail -> {
          assertThat(detail.get("receivableId")).isEqualTo(30L);
          assertThat(detail.get("head")).isEqualTo("Instalment");
          assertThat((BigDecimal) detail.get("amount")).isEqualByComparingTo("50.00");
        });
    Map<String, Object> totals = (Map<String, Object>) report.get("totals");
    assertThat((BigDecimal) totals.get("charges")).isEqualByComparingTo("200.00");
    assertThat((BigDecimal) totals.get("paid")).isEqualByComparingTo("110.00");
    assertThat((BigDecimal) totals.get("adjustments")).isEqualByComparingTo("10.00");
    assertThat((BigDecimal) totals.get("surcharge")).isEqualByComparingTo("5.00");
    assertThat((BigDecimal) totals.get("outstanding")).isEqualByComparingTo("85.00");
  }

  @Test void originalReceiptShowsItsAuditedSalesMergeReassociation() {
    jdbc.update("INSERT INTO webv9_sales_merge_transfers VALUES (80,21,'DOL-00101')");
    jdbc.update("INSERT INTO webv9_sales_merge_transfer_lines VALUES (81,80,60,31,50)");

    Map<String, Object> report = reports.generate(request(20L, LocalDate.parse("2026-09-30"), Map.of()));
    List<Map<String, Object>> sales = (List<Map<String, Object>>) report.get("sales");
    List<Map<String, Object>> receipts = (List<Map<String, Object>>) sales.get(0).get("receipts");
    List<Map<String, Object>> details = (List<Map<String, Object>>) receipts.get(0).get("allocationDetails");

    assertThat(details).singleElement().satisfies(detail -> {
      assertThat(detail.get("salesTransferId")).isEqualTo(81L);
      assertThat(detail.get("transferredToBookingId")).isEqualTo(21L);
      assertThat(detail.get("transferredToPlotNo")).isEqualTo("DOL-00101");
      assertThat((BigDecimal) detail.get("transferredAmount")).isEqualByComparingTo("50.00");
    });
  }

  @Test void selectedSaleIsMemberScopedAndReceiptPresentationCanBeHiddenWithoutChangingTotals() {
    Map<String, Object> report = reports.generate(request(20L, LocalDate.parse("2026-09-30"),
        Map.of("hideReceipt", true, "hideAddressMobile", true)));
    List<Map<String, Object>> sales = (List<Map<String, Object>>) report.get("sales");
    assertThat(sales).hasSize(1).first().satisfies(s -> assertThat(s.get("bookingId")).isEqualTo(20L));
    assertThat((Map<String, Object>) report.get("customer")).doesNotContainKeys("address1", "contact1", "landline1");
    assertThat((List<Map<String, Object>>) sales.get(0).get("receipts")).isEmpty();
    assertThat((BigDecimal) ((Map<String, Object>) report.get("totals")).get("outstanding")).isEqualByComparingTo("45.00");
  }

  @Test void cutoffUsesDueDateAndLedgerPostingDateAndListsUnallocatedReceiptSeparately() {
    Map<String, Object> report = reports.generate(request(null, LocalDate.parse("2026-09-30"), Map.of()));
    List<Map<String, Object>> sales = (List<Map<String, Object>>) report.get("sales");
    assertThat((List<Map<String, Object>>) sales.get(0).get("ledgerTransactions")).hasSize(1);
    assertThat((List<Map<String, Object>>) report.get("unallocatedReceipts"))
        .singleElement().satisfies(receipt -> {
          assertThat(receipt.get("receiptNo")).isEqualTo("R-41");
          assertThat(receipt.get("receiptDate")).isEqualTo(java.sql.Date.valueOf("2026-09-29"));
          assertThat(receipt.get("salesClassification")).isNull();
          assertThat(receipt.get("bankInstrumentType")).isNull();
        });
    Map<String, Object> afterCutoff = reports.generate(request(null, LocalDate.parse("2026-10-02"), Map.of()));
    assertThat((List<Map<String, Object>>) afterCutoff.get("unallocatedReceipts")).hasSize(1);
    assertThat((Map<String, Object>) afterCutoff.get("totals")).containsEntry("outstanding", new BigDecimal("85.00"));
  }

  @Test void receiptApplicationRemainsVisibleWhenLegacyAllocationHasNoReceivableReservation() {
    jdbc.update("UPDATE webv9_payment_allocations SET receivable_id=NULL WHERE id=50");

    Map<String, Object> report = reports.generate(request(20L, LocalDate.parse("2026-09-30"), Map.of()));
    List<Map<String, Object>> sales = (List<Map<String, Object>>) report.get("sales");
    List<Map<String, Object>> receipts = (List<Map<String, Object>>) sales.get(0).get("receipts");

    assertThat(receipts).hasSize(1).first().satisfies(receipt -> {
      assertThat(receipt.get("receiptNo")).isEqualTo("R-40");
      assertThat(receipt.get("receiptDate")).isEqualTo(java.sql.Date.valueOf("2026-09-19"));
      assertThat((List<Map<String, Object>>) receipt.get("allocationDetails"))
          .singleElement().satisfies(detail -> {
            assertThat(detail.get("receivableId")).isEqualTo(30L);
            assertThat((BigDecimal) detail.get("amount")).isEqualByComparingTo("50.00");
          });
    });
    assertThat((List<Map<String, Object>>) report.get("unallocatedReceipts"))
        .singleElement().satisfies(receipt -> assertThat(receipt.get("receiptNo")).isEqualTo("R-41"));
  }

  @Test void postedReceiptWithSaleAllocationButNoApplicationIsKeptWithItsSale() {
    jdbc.update("DELETE FROM webv9_payment_receivable_applications WHERE allocation_id=50");
    jdbc.update("UPDATE webv9_payment_allocations SET receivable_id=NULL WHERE id=50");

    Map<String, Object> report = reports.generate(request(20L, LocalDate.parse("2026-09-30"), Map.of()));
    List<Map<String, Object>> sales = (List<Map<String, Object>>) report.get("sales");
    List<Map<String, Object>> receipts = (List<Map<String, Object>>) sales.get(0).get("receipts");

    assertThat(receipts).hasSize(1).first().satisfies(receipt -> {
      assertThat(receipt.get("receiptNo")).isEqualTo("R-40");
      assertThat((BigDecimal) receipt.get("allocatedAmount")).isEqualByComparingTo("50.00");
      assertThat((List<Map<String, Object>>) receipt.get("allocationDetails"))
          .singleElement().satisfies(detail -> {
            assertThat(detail.get("receivableId")).isNull();
            assertThat(detail.get("head")).isEqualTo("Instalment");
          });
    });
    assertThat((List<Map<String, Object>>) report.get("unallocatedReceipts"))
        .singleElement().satisfies(receipt -> assertThat(receipt.get("receiptNo")).isEqualTo("R-41"));
  }

  @Test void invalidBookingCannotBeQueriedUnderAnotherMemberAndNoSaleCustomerGetsZeroTotals() {
    assertThatThrownBy(() -> reports.generate(request(999L, LocalDate.parse("2026-09-30"), Map.of())))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("does not belong");
    jdbc.update("INSERT INTO members VALUES (2,'M-2','No Sale','CNIC-2',NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
    Map<String, Object> empty = reports.generate(new CustomerLedgerReportRequest(2L, null, LocalDate.parse("2026-09-30"), "REGULAR", Map.of()));
    assertThat((List<Map<String, Object>>) empty.get("sales")).isEmpty();
    assertThat((BigDecimal) ((Map<String, Object>) empty.get("totals")).get("outstanding")).isEqualByComparingTo("0.00");
  }

  @Test void unknownLegacyOptionsAreReportedAndNotUsedAsFilters() {
    Map<String, Object> report = reports.generate(request(null, LocalDate.parse("2026-09-30"), Map.of(
        "onlyActiveSale", true, "hideLps", true, "hidePartner", true, "completeTransactions", true, "clearedTranOnly", true)));
    assertThat((List<Map<String, Object>>) report.get("sales")).hasSize(2);
    assertThat((List<String>) report.get("warnings")).anyMatch(w -> w.contains("not established"));
  }

  @Test void fullyPaidCustomerShowsZeroOutstandingAndTotalsEqualSaleSums() {
    jdbc.update("UPDATE webv9_receivables SET paid_amount=95 WHERE id=30");
    jdbc.update("UPDATE webv9_receivables SET paid_amount=100 WHERE id=31");
    Map<String, Object> report = reports.generate(request(null, LocalDate.parse("2026-09-30"), Map.of()));
    BigDecimal saleSum = ((List<Map<String, Object>>) report.get("sales")).stream()
        .map(sale -> (BigDecimal) ((Map<String, Object>) sale.get("totals")).get("outstanding"))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(saleSum).isEqualByComparingTo("0.00");
    assertThat((BigDecimal) ((Map<String, Object>) report.get("totals")).get("outstanding")).isEqualByComparingTo(saleSum);
  }

  private static CustomerLedgerReportRequest request(Long booking, LocalDate toDate, Map<String, Boolean> options) {
    return new CustomerLedgerReportRequest(1L, booking, toDate, "REGULAR", options);
  }
}
