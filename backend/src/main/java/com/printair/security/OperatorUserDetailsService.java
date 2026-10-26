package com.printair.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class OperatorUserDetailsService implements UserDetailsService {
    private final ShopOperatorRepository operators;

    public OperatorUserDetailsService(ShopOperatorRepository operators) {
        this.operators = operators;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var operator = operators.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Operator not found"));
        return new OperatorPrincipal(operator.getId(), operator.getShop().getId(), operator.getEmail(),
                operator.getPasswordHash(), operator.isEnabled());
    }
}
