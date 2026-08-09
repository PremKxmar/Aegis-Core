package com.aegis.policy.application;

import java.time.LocalDate;
import java.util.List;

/**
 * @param effectiveFrom may be in the past. A backdated endorsement takes effect from this date
 *     while remaining recorded as having been keyed in today.
 * @param coverages the complete set of coverage lines from {@code effectiveFrom} onward, not a
 *     delta. Stating the full set is what makes each version independently readable — a claim
 *     handler resolving one version should never have to replay a chain of deltas to learn what
 *     was covered.
 */
public record EndorsePolicyCommand(
        LocalDate effectiveFrom,
        String insuredName,
        String territory,
        String changeReason,
        List<CoverageCommand> coverages) {}
