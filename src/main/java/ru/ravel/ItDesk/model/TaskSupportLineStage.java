package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;

@Entity
@Table(name = "task_support_line_stage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TaskSupportLineStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "support_line_id")
    private SupportLine supportLine;

    @Column(name = "entered_at", nullable = false)
    private ZonedDateTime enteredAt;

    @Column(name = "left_at")
    private ZonedDateTime leftAt;

    @Column(name = "ola_deadline")
    private ZonedDateTime olaDeadline;

    @Column(name = "ola_warning_at")
    private ZonedDateTime olaWarningAt;

    @Column(name = "breached_at")
    private ZonedDateTime breachedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private OlaStatus status = OlaStatus.DISABLED;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "use_working_time", nullable = false)
    @Builder.Default
    private Boolean useWorkingTime = false;

    @Column(name = "paused_seconds", nullable = false)
    @Builder.Default
    private Long pausedSeconds = 0L;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "transferred_by_user_id")
    private User transferredBy;

    @Column(name = "transfer_reason", length = 1024)
    private String transferReason;
}
