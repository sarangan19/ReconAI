package com.reconai.recon.repository;

import com.reconai.recon.domain.CanonicalTxn;
import com.reconai.recon.domain.TxnSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CanonicalTxnRepository extends JpaRepository<CanonicalTxn, Long> {

    long countByBatchId(Long batchId);

    long countByBatchIdAndSide(Long batchId, TxnSide side);

    @Query(value = "SELECT COUNT(*) FROM canonical_txn WHERE batch_id = :batchId AND side = :side",
           nativeQuery = true)
    long countByBatchAndSideNative(@Param("batchId") long batchId, @Param("side") String side);
}
