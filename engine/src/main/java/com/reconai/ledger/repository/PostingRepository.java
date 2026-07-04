package com.reconai.ledger.repository;

import com.reconai.ledger.domain.Posting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    @Query(value = """
        SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
        FROM posting WHERE account_id = :accountId
        """, nativeQuery = true)
    BigDecimal getBalance(@Param("accountId") long accountId);
}
