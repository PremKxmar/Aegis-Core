package com.aegis.policy.repository;

import com.aegis.policy.domain.Policy;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    Optional<Policy> findByPolicyNumber(String policyNumber);

    boolean existsByPolicyNumber(String policyNumber);

    /**
     * Loads a policy for writing, taking an optimistic lock that increments even though the
     * {@code policy} row itself is not modified.
     *
     * <p>Endorsing appends rows to {@code policy_version} and leaves {@code policy} untouched,
     * so Hibernate would not consider the aggregate root dirty and {@code @Version} would never
     * increment — the lock would be present in the schema and doing nothing. Forcing the
     * increment makes two concurrent endorsements conflict, which is the entire point: both
     * would otherwise read the same current chain, both would compute a valid-looking new
     * timeline from it, and the second commit would leave a gap or an overlap behind.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select p from Policy p where p.policyNumber = :policyNumber")
    Optional<Policy> findByPolicyNumberForUpdate(@Param("policyNumber") String policyNumber);

    /**
     * Loads a policy together with its whole version history in one query.
     *
     * <p>{@code versions} is lazy and {@code open-in-view} is off, so a caller that reads the
     * history after the transaction closes gets a LazyInitializationException — which is the
     * setting doing its job. This query makes the fetch explicit instead of accidental, and the
     * join avoids the N+1 that fetching each version separately would produce.
     */
    @Query("select distinct p from Policy p left join fetch p.versions where p.policyNumber = :policyNumber")
    Optional<Policy> findByPolicyNumberWithHistory(@Param("policyNumber") String policyNumber);

    /**
     * The as-of resolution expressed in the database rather than in Java.
     *
     * <p>This is the same predicate as {@code PolicyVersionResolver.resolve}, and
     * {@code PolicyResolutionParityIT} asserts the two agree across the whole resolution table.
     * Two implementations of one rule is a cost worth paying here: the Java version can be
     * unit-tested exhaustively in milliseconds, and the SQL version is what keeps the query
     * indexable instead of loading a policy's entire history to answer one coverage question.
     *
     * <p>Returns a list rather than an Optional so that an overlap in the data surfaces as a
     * detectable fault rather than as an arbitrary pick by {@code LIMIT 1}.
     */
    @Query("""
            select v from PolicyVersion v
            left join fetch v.coverages
            where v.policy.policyNumber = :policyNumber
              and v.effectiveFrom <= :asOf
              and (v.effectiveTo is null or v.effectiveTo > :asOf)
              and v.recordedAt <= :asAt
              and (v.supersededAt is null or v.supersededAt > :asAt)
            """)
    List<com.aegis.policy.domain.PolicyVersion> resolveVersions(
            @Param("policyNumber") String policyNumber, @Param("asOf") LocalDate asOf, @Param("asAt") Instant asAt);
}
