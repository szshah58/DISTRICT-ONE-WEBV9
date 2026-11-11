package com.districtone.webv9.model;
import jakarta.persistence.*; import lombok.*; import java.math.BigDecimal;
@Entity @Table(name="plots") @Getter @Setter @NoArgsConstructor
public class Plot {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="plot_id") private Long id;
 @ManyToOne @JoinColumn(name="block_id", nullable=false) private Block block;
 @Column(name="plot_no", length=50, nullable=false) private String plotNo;
 @Column(name="plot_size_marla", precision=6, scale=2) private BigDecimal sizeMarla;
 @Column(name="plot_type", length=50) private String usageType;
 @Column(length=40, nullable=false) private String status="AVAILABLE";
}
