package com.aegis.policy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Policy administration.
 *
 * <p>Owns the policy lifecycle: binding, mid-term endorsement and cancellation. A policy is
 * never updated in place — every change appends a new effective-dated version — so that a
 * claim filed months later can be assessed against the terms that were actually in force on
 * its loss date.
 */
@SpringBootApplication
public class PolicyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyServiceApplication.class, args);
    }
}
