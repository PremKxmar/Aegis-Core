package com.aegis.policy.application;

import java.time.LocalDate;
import java.util.List;

/**
 * @param expiryDate exclusive — the first day no longer covered. {@code null} for a policy with
 *     no scheduled expiry.
 */
public record BindPolicyCommand(
        String policyNumber,
        String productCode,
        LocalDate inceptionDate,
        LocalDate expiryDate,
        String insuredName,
        String territory,
        List<CoverageCommand> coverages) {}
