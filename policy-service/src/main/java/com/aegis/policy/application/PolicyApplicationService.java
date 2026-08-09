package com.aegis.policy.application;

import com.aegis.policy.domain.Coverage;
import com.aegis.policy.domain.DuplicatePolicyNumberException;
import com.aegis.policy.domain.Policy;
import com.aegis.policy.domain.PolicyNotFoundException;
import com.aegis.policy.domain.PolicyVersion;
import com.aegis.policy.repository.PolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundary and clock boundary for the policy aggregate.
 *
 * <p>All the temporal reasoning lives in {@link Policy}; this class exists to decide *when*
 * "now" is and to make each write one database transaction. The domain never reads the clock
 * itself — every method that records something takes the instant as a parameter — which is what
 * lets the whole of {@code PolicyTemporalResolutionTest} run against fixed dates with no
 * mocking and no flakiness at midnight.
 */
@Service
public class PolicyApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PolicyApplicationService.class);

    private final PolicyRepository policies;
    private final Clock clock;

    public PolicyApplicationService(PolicyRepository policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    @Transactional
    public Policy bind(BindPolicyCommand command) {
        if (policies.existsByPolicyNumber(command.policyNumber())) {
            throw new DuplicatePolicyNumberException(command.policyNumber());
        }
        Instant recordedAt = clock.instant();
        Policy policy = Policy.bind(
                command.policyNumber(),
                command.productCode(),
                command.inceptionDate(),
                command.expiryDate(),
                recordedAt,
                command.insuredName(),
                command.territory(),
                toCoverages(command.coverages()));

        Policy saved = policies.save(policy);
        log.info(
                "Bound policy {} effective {} to {} with {} coverage line(s)",
                saved.policyNumber(),
                command.inceptionDate(),
                command.expiryDate(),
                command.coverages().size());
        return saved;
    }

    @Transactional
    public Policy endorse(String policyNumber, EndorsePolicyCommand command) {
        Policy policy = loadForUpdate(policyNumber);
        Instant recordedAt = clock.instant();

        policy.endorse(
                command.effectiveFrom(),
                recordedAt,
                command.insuredName(),
                command.territory(),
                command.changeReason(),
                toCoverages(command.coverages()));

        log.info(
                "Endorsed policy {} effective {} (recorded {}){}",
                policyNumber,
                command.effectiveFrom(),
                recordedAt,
                command.effectiveFrom().isBefore(LocalDate.ofInstant(recordedAt, clock.getZone()))
                        ? " - BACKDATED"
                        : "");
        return policy;
    }

    @Transactional
    public Policy cancel(String policyNumber, CancelPolicyCommand command) {
        Policy policy = loadForUpdate(policyNumber);
        policy.cancel(command.effectiveFrom(), clock.instant(), command.reason());

        log.info("Cancelled policy {} effective {}: {}", policyNumber, command.effectiveFrom(), command.reason());
        return policy;
    }

    /**
     * The version in force on {@code asOf}, as understood at {@code asAt}.
     *
     * @param asAt {@code null} means "as we understand it now"
     */
    @Transactional(readOnly = true)
    public Optional<PolicyVersion> resolveVersion(String policyNumber, LocalDate asOf, Instant asAt) {
        Policy policy =
                policies.findByPolicyNumber(policyNumber).orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        return policy.versionAsOf(asOf, asAt == null ? clock.instant() : asAt);
    }

    @Transactional(readOnly = true)
    public Policy findByNumber(String policyNumber) {
        return policies.findByPolicyNumber(policyNumber).orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    /**
     * Every version ever recorded, superseded ones included, ordered by when it was recorded.
     *
     * <p>Sorted and fully fetched inside the transaction on purpose. The controller renders the
     * result after the transaction has closed, and {@code open-in-view} is off, so anything not
     * loaded here would fail rather than quietly issuing another query per row.
     */
    @Transactional(readOnly = true)
    public List<PolicyVersion> history(String policyNumber) {
        Policy policy = policies.findByPolicyNumberWithHistory(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
        return policy.allVersions().stream()
                .sorted(Comparator.comparing(PolicyVersion::recordedAt).thenComparing(PolicyVersion::versionNumber))
                .toList();
    }

    private Policy loadForUpdate(String policyNumber) {
        return policies.findByPolicyNumberForUpdate(policyNumber)
                .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    private static List<Coverage> toCoverages(Collection<CoverageCommand> commands) {
        return commands.stream()
                .map(c -> new Coverage(c.coverageCode(), c.limit(), c.deductible(), c.exclusions()))
                .toList();
    }
}
