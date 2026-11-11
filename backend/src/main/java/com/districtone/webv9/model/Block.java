package com.districtone.webv9.model;
import jakarta.persistence.*; import lombok.*;
@Entity @Table(name="blocks") @Getter @Setter @NoArgsConstructor public class Block { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="block_id") private Long id; @Column(name="block_name", length=100) private String name; }
