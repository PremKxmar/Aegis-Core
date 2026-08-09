package com.aegis.rating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Rating.
 *
 * <p>Turns a risk description into a premium using versioned, effective-dated rate tables.
 * The calculation is a pure function of its inputs — no wall-clock reads, no randomness — so
 * that re-rating the same risk against the same rate table version always reproduces the same
 * number, and every premium can be explained factor by factor from its stored worksheet.
 */
@SpringBootApplication
public class RatingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RatingServiceApplication.class, args);
    }
}
