package ru.lct.heatnet.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface JobRepository extends JpaRepository<JobEntity, String> {
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update JobEntity j set j.status = :running, j.startedAt = :now, j.progress = 'Parsing input' where j.id = :id and j.status = :queued")
    int claimQueuedStatus(@org.springframework.data.repository.query.Param("id") String id,
                    @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                    @org.springframework.data.repository.query.Param("queued") JobEntity.Status queued,
                    @org.springframework.data.repository.query.Param("running") JobEntity.Status running);

    default int claimQueued(String id, java.time.Instant now) {
        return claimQueuedStatus(id, now, JobEntity.Status.QUEUED, JobEntity.Status.RUNNING);
    }

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update JobEntity j set j.status = :failed, j.progress = 'upload-queue-retry', j.error = 'Queue temporarily unavailable; complete the retained upload again', j.finishedAt = :now where j.id = :id and j.status = :queued and j.startedAt is null")
    int rejectUploadQueueStatus(@org.springframework.data.repository.query.Param("id") String id,
                          @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                          @org.springframework.data.repository.query.Param("queued") JobEntity.Status queued,
                          @org.springframework.data.repository.query.Param("failed") JobEntity.Status failed);
    default int rejectUploadQueue(String id, java.time.Instant now) {
        return rejectUploadQueueStatus(id, now, JobEntity.Status.QUEUED, JobEntity.Status.FAILED);
    }
    List<JobEntity> findTop50ByOrderByCreatedAtDesc();
    List<JobEntity> findTop100ByStatusIn(Collection<JobEntity.Status> statuses);
}
