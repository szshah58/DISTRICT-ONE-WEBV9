package com.districtone.webv9.model;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="roles") @Getter @Setter @NoArgsConstructor
public class Role {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="role_name", length=100, nullable=false, unique=true) private String name;
}
