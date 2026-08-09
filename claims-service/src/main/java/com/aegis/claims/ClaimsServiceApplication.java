package com.aegis.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Claims.
 *
 * <p>Handles first notice of loss, drives each claim through a guarded state machine, and
 * keeps an append-only financial ledger of reserves, payments and recoveries. Coverage is
 * verified against the policy version in force on the loss date, not the current one.
 */
@SpringBootApplication
public class ClaimsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimsServiceApplication.class, args);
    }
}
