package com.printair.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
public class CafeRegistrationController {
    private final CafeRegistrationService registration;

    public CafeRegistrationController(CafeRegistrationService registration) {
        this.registration = registration;
    }

    @PostMapping("/register")
    ResponseEntity<Void> register(@Valid @RequestBody RegisterCafeRequest request) {
        registration.register(request.shopName(), request.email(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resend-verification")
    ResponseEntity<Void> resend(@Valid @RequestBody ResendVerificationRequest request) {
        registration.resendVerification(request.email());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email")
    ResponseEntity<Void> verify(@Valid @RequestBody VerifyEmailRequest request) {
        registration.verify(request.token());
        return ResponseEntity.noContent().build();
    }

    public record RegisterCafeRequest(
            @NotBlank @Size(max = 120) String shopName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
        @AssertTrue(message = "Password must not exceed 72 UTF-8 bytes")
        public boolean isPasswordByteLengthValid() {
            return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }

    public record ResendVerificationRequest(
            @NotBlank @Email @Size(max = 254) String email) {
    }

    public record VerifyEmailRequest(
            @NotBlank @Size(max = 128) String token) {
    }
}
