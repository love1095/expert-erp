package com.experterp.backend.dto;

import lombok.Data;

@Data
public class RegisterRequest {
    private String organizationName; // creates new tenant on registration
    private String username;
    private String email;
    private String password;
    private String role; // for adding users to existing tenant (admin use)
}
