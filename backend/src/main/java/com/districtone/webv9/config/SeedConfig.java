package com.districtone.webv9.config;

import com.districtone.webv9.model.Role;
import com.districtone.webv9.model.User;
import com.districtone.webv9.repo.RoleRepository;
import com.districtone.webv9.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SeedConfig {
  @Bean
  CommandLineRunner seed(RoleRepository roles, UserRepository users, PasswordEncoder encoder,
                         @Value("${WEBV9_SEED_PASSWORD:}") String password) {
    return args -> {
      if (users.count() == 0 && !password.isBlank()) {
        User user = new User();
        user.setUsername("superadmin");
        user.setFullName("District One Administrator");
        user.setPasswordHash(encoder.encode(password));
        user.setRole("SUPER_ADMIN");
        user.setStatus("ACTIVE");
        users.save(user);
      }
    };
  }
}
