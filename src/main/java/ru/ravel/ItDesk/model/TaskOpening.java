package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.ZonedDateTime;

@Entity
@Table(name = "task_user_open")
@IdClass(TaskOpening.Key.class)
@Immutable
@Getter
@NoArgsConstructor
public class TaskOpening {
    @Id
    @Column(name = "task_id")
    private Long taskId;

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "opened_at", nullable = false)
    private ZonedDateTime openedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long taskId;
        private Long userId;
    }
}
