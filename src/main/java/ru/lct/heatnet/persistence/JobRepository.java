package ru.lct.heatnet.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface JobRepository extends JpaRepository<JobEntity, String> {
    List<JobEntity> findTop50ByOrderByCreatedAtDesc();
    List<JobEntity> findTop100ByStatusIn(Collection<JobEntity.Status> statuses);
}
