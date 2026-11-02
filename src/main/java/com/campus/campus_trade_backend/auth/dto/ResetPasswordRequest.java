package com.campus.campus_trade_backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@Email @NotBlank String email,
                                   @NotBlank @Pattern(regexp = "[0-9]{6}") String code,
                                   @NotBlank @Size(min = 8, max = 72) String newPassword) { }
