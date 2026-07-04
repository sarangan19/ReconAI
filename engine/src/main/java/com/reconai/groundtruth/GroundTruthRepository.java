package com.reconai.groundtruth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroundTruthRepository extends JpaRepository<GroundTruth, Long> {
    long countByBatchId(Long batchId);
}
