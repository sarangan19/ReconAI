package com.reconai.breaks;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BreakRepository extends JpaRepository<ReconBreak, Long> {

    Page<ReconBreak> findByBatchId(Long batchId, Pageable pageable);

    Page<ReconBreak> findByBatchIdAndStatus(Long batchId, String status, Pageable pageable);

    Page<ReconBreak> findByBatchIdAndDetectedType(Long batchId, String detectedType, Pageable pageable);

    long countByBatchId(Long batchId);

    @Query("SELECT b.detectedType, COUNT(b) FROM ReconBreak b WHERE b.batchId = :batchId GROUP BY b.detectedType")
    java.util.List<Object[]> countByTypeForBatch(@Param("batchId") Long batchId);
}
