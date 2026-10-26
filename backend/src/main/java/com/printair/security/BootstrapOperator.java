package com.printair.security;

import com.printair.shop.Shop;
import com.printair.shop.ShopRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapOperator implements CommandLineRunner {
    private final Environment environment;
    private final ShopRepository shops;
    private final ShopOperatorRepository operators;
    private final PasswordEncoder passwordEncoder;

    public BootstrapOperator(Environment environment, ShopRepository shops, ShopOperatorRepository operators,
                             PasswordEncoder passwordEncoder) {
        this.environment = environment;
        this.shops = shops;
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        var slug = environment.getProperty("BOOTSTRAP_SHOP_SLUG");
        var name = environment.getProperty("BOOTSTRAP_SHOP_NAME");
        var email = environment.getProperty("BOOTSTRAP_OPERATOR_EMAIL");
        var password = environment.getProperty("BOOTSTRAP_OPERATOR_PASSWORD");
        int configured = (slug == null ? 0 : 1) + (name == null ? 0 : 1) + (email == null ? 0 : 1)
                + (password == null ? 0 : 1);
        if (configured == 0) return;
        if (configured != 4 || password.length() < 12) {
            throw new IllegalStateException("All bootstrap shop/operator settings are required; password must be at least 12 characters");
        }
        if (operators.findByEmailIgnoreCase(email).isPresent()) return;
        var shop = shops.findBySlug(slug).orElseGet(() -> shops.save(new Shop(name, slug)));
        operators.save(new ShopOperator(shop, email, passwordEncoder.encode(password)));
    }
}
