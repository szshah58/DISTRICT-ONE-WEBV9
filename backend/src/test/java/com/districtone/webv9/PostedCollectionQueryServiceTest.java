package com.districtone.webv9;

import com.districtone.webv9.service.PostedCollectionQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PostedCollectionQueryServiceTest {
  private PostedCollectionQueryService query;

  @BeforeEach
  void setUp() {
    var dataSource = new DriverManagerDataSource("jdbc:h2:mem:posted_collections;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP TABLE IF EXISTS payments_receipts");
    jdbc.execute("DROP TABLE IF EXISTS webv9_payment_allocations");
    jdbc.execute("CREATE TABLE payments_receipts(id BIGINT PRIMARY KEY, amount DECIMAL(16,2), status VARCHAR(32), payment_date DATE, posted_at TIMESTAMP)");
    jdbc.execute("CREATE TABLE webv9_payment_allocations(id BIGINT PRIMARY KEY, payment_id BIGINT, allocated_amount DECIMAL(16,2))");
    jdbc.update("INSERT INTO payments_receipts VALUES (1,100.00,'POSTED','2026-05-01','2026-07-01 00:00:00'), (2,200.00,'POSTED','2026-06-01','2026-07-31 23:59:59'), (3,500.00,'VERIFIED','2026-07-10','2026-07-15 12:00:00'), (4,700.00,'PENDING','2026-07-10','2026-07-16 12:00:00'), (5,300.00,'POSTED','2026-07-31','2026-08-01 00:00:00')");
    jdbc.update("INSERT INTO webv9_payment_allocations VALUES (11,1,50.00), (12,1,50.00), (21,2,200.00)");
    query = new PostedCollectionQueryService(jdbc);
  }

  @Test
  void julyCollectionsUsePostedAtAndCountEachReceiptHeaderOnce() {
    BigDecimal july = query.sumBetween(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));

    assertThat(july).isEqualByComparingTo("300.00");
  }

  @Test
  void verifiedUnpostedReceiptsAreNotLifetimeCollections() {
    assertThat(query.sumAllPosted()).isEqualByComparingTo("600.00");
  }
}
