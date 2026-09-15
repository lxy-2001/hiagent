package com.agentflow.web.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentTaskRepository extends JpaRepository<AgentTaskEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AgentTaskEntity task where task.id = :taskId")
    Optional<AgentTaskEntity> findByIdForUpdate(@Param("taskId") String taskId);

    @Query("select task from AgentTaskEntity task "
            + "where task.status in :statuses and task.id > :afterId order by task.id")
    List<AgentTaskEntity> findInterruptedAfter(@Param("statuses") Collection<String> statuses,
                                               @Param("afterId") String afterId,
                                               Pageable pageable);
}
