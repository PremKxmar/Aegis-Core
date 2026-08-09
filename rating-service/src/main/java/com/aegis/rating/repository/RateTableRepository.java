package com.aegis.rating.repository;

import com.aegis.rating.domain.RateTable;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RateTableRepository extends JpaRepository<RateTable, UUID> {

    /**
     * The rate tables in force for a product on a date. Half-open period, same convention as
     * policy versions.
     *
     * <p>Returns a list rather than an Optional so that overlapping published tables surface as a
     * detectable fault. A {@code LIMIT 1} here would silently pick one of two contradictory sets
     * of filed rates and price the risk from it.
     */
    @Query("""
            select t from RateTable t
            where t.productCode = :productCode
              and t.effectiveFrom <= :date
              and (t.effectiveTo is null or t.effectiveTo > :date)
            """)
    List<RateTable> findEffectiveOn(@Param("productCode") String productCode, @Param("date") LocalDate date);

    List<RateTable> findByProductCodeOrderByTableVersionAsc(String productCode);
}
