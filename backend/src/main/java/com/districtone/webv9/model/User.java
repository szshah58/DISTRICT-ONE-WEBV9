package com.districtone.webv9.model;
import jakarta.persistence.*;
@Entity @Table(name="users") 
public class User {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false, unique=true, length=80) private String username;
 @Column(name="password_hash", nullable=false, length=100) private String passwordHash;
 @Column(name="display_name", length=150, nullable=false) private String fullName;
 @Column(length=180) private String email;
 @Column(name="mobile", length=40) private String contact;
 @Column(name="is_active") private boolean active;
 @Column(name="role", length=50, nullable=false) private String role;
 public String getStatus() { return active ? "ACTIVE" : "INACTIVE"; }
 public void setStatus(String status) { this.active = "ACTIVE".equals(status); }
 public User() {}
 public Long getId() { return id; }
 public void setId(Long id) { this.id = id; }
 public String getUsername() { return username; }
 public void setUsername(String username) { this.username = username; }
 public String getPasswordHash() { return passwordHash; }
 public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
 public String getFullName() { return fullName; }
 public void setFullName(String fullName) { this.fullName = fullName; }
 public String getEmail() { return email; }
 public void setEmail(String email) { this.email = email; }
 public String getContact() { return contact; }
 public void setContact(String contact) { this.contact = contact; }
 public boolean isActive() { return active; }
 public void setActive(boolean active) { this.active = active; }
 public String getRole() { return role; }
 public void setRole(String role) { this.role = role; }
}

