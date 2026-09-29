package ru.lct.heatnet.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRepository extends JpaRepository<JobEntity, String> {
    List<JobEntity> findTop50ByOrderByCreatedAtDesc();
}
