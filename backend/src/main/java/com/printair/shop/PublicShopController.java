package com.printair.shop;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public/shops")
public class PublicShopController {
    private final ShopRepository shops;

    public PublicShopController(ShopRepository shops) {
        this.shops = shops;
    }

    @GetMapping("/{slug}")
    ShopProfile profile(@PathVariable String slug) {
        Shop shop = shops.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new ShopProfile(shop.getName());
    }

    public record ShopProfile(String shopName) {
    }
}
