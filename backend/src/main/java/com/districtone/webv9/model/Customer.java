package com.districtone.webv9.model;
import jakarta.persistence.*; import lombok.*; import java.time.Instant;
@Entity @Table(name="members") @Getter @Setter @NoArgsConstructor
public class Customer {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="member_id") private Long id; @Column(name="mbr_no", length=30, nullable=false,unique=true) private String mbrNo;
 @Transient private String regNo; @Transient private String formNo; @Transient private String category;
 @Column(name="name", length=200, nullable=false) private String name; @Column(name="cnic_no", length=30, nullable=false) private String cnicNo;
 @Column(name="contact1", length=50) private String contact1; @Column(name="contact2", length=50) private String contact2;
 @Column(name="address1", length=500) private String address; @Column(length=100) private String city;
 @Column(name="doc_status", length=50) private String status="ACTIVE";
 @Column(name="created_at") private Instant createdAt; @Column(name="updated_at") private Instant updatedAt;
}
