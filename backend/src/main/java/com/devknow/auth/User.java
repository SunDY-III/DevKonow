package com.devknow.auth;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "sys_user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password;
    private String role;            // USER / HANDLER / ADMIN
    private String knowledgeRole;    // 知识职责角色: ARCHITECT / DEVELOPER / QA / DEVOPS / PM / UNSPECIFIED
    @Column(insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
