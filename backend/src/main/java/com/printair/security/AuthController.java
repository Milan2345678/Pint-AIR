package com.printair.security;

import com.printair.shop.ShopRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.env.Environment;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final ShopRepository shops;
    private final String publicBaseUrl;

    public AuthController(ShopRepository shops, Environment environment) {
        this.shops = shops;
        this.publicBaseUrl = environment.getProperty("printair.public-base-url", "http://localhost:5174")
                .replaceAll("/+$", "");
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/me")
    Map<String, String> me(@AuthenticationPrincipal OperatorPrincipal operator) {
        var shop = shops.findById(operator.shopId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return Map.of("email", operator.email(), "shopName", shop.getName(), "shopSlug", shop.getSlug(),
                "submissionUrl", publicBaseUrl + "/shop/" + shop.getSlug());
    }
}
