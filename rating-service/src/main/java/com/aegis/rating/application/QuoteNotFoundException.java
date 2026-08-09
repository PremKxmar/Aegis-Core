package com.aegis.rating.application;

/** Thrown when no quote exists with the given reference. */
public class QuoteNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String quoteNumber;

    public QuoteNotFoundException(String quoteNumber) {
        super("No quote found with number " + quoteNumber);
        this.quoteNumber = quoteNumber;
    }

    public String quoteNumber() {
        return quoteNumber;
    }
}
