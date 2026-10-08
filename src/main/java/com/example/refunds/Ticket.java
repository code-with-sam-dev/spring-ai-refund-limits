package com.example.refunds;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The support ticket a request belongs to, from the signed token.
 * The customer comes from here and nowhere else.
 */
public record Ticket(String customerId, String agent) {

    public static Ticket current() {
        var auth = SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("no authenticated ticket");
        }
        return new Ticket(jwt.getClaimAsString("customer_id"),
                jwt.getSubject());
    }
}
