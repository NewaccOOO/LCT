package ru.lct.heatnet.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;

@Profile("!cli")
public interface JobRepository extends JpaRepository<JobEntity, UUID> {
    List<JobEntity> findAllByOrderByCreatedAtDesc();

    List<JobEntity> findByStatusIn(Collection<JobEntity.Status> statuses);
}
