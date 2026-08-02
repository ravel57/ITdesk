package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.TaskSupportLineStage;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TaskSupportLineStageRepository extends JpaRepository<TaskSupportLineStage, Long> {

    Optional<TaskSupportLineStage> findFirstByTaskIdAndLeftAtIsNullOrderByEnteredAtDesc(Long taskId);

    @Query("""
            select s
            from TaskSupportLineStage s
            where s.enteredAt <= :to
              and (s.leftAt is null or s.leftAt >= :from)
              and (:hasLineIds = false or s.supportLine.id in :lineIds)
            order by s.task.id asc, s.enteredAt asc
            """)
    List<TaskSupportLineStage> findOverlappingPeriod(
            @Param("from") ZonedDateTime from,
            @Param("to") ZonedDateTime to,
            @Param("hasLineIds") boolean hasLineIds,
            @Param("lineIds") Set<Long> lineIds
    );
}
