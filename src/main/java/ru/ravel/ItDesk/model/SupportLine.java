package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@Entity
@Table(name = "support_line")
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SupportLine implements Comparable<SupportLine> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 1024)
    @Builder.Default
    private String description = "";

    @Column(nullable = false)
    @Builder.Default
    private Integer level = 1;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "default_selection", nullable = false)
    @Builder.Default
    private Boolean defaultSelection = false;

    @Column(name = "order_number", nullable = false)
    @Builder.Default
    private Integer orderNumber = 0;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "responsible_user_id")
    private User responsible;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "support_line_members",
            joinColumns = @JoinColumn(name = "support_line_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    private List<User> members = new ArrayList<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "support_line_observers",
            joinColumns = @JoinColumn(name = "support_line_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    private Set<User> observers = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_strategy", nullable = false, length = 32)
    @Builder.Default
    private SupportLineAssignmentStrategy assignmentStrategy = SupportLineAssignmentStrategy.KEEP_UNASSIGNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_mode", nullable = false, length = 40)
    @Builder.Default
    private SupportLineVisibilityMode visibilityMode = SupportLineVisibilityMode.INHERIT;

    @Column(name = "allow_self_assignment", nullable = false)
    @Builder.Default
    private Boolean allowSelfAssignment = true;

    @Column(name = "notify_on_new_task", nullable = false)
    @Builder.Default
    private Boolean notifyOnNewTask = true;

    @Column(name = "capacity_per_member", nullable = false)
    @Builder.Default
    private Integer capacityPerMember = 0;

    @Column(name = "round_robin_cursor")
    private Long roundRobinCursor;

    @Column(name = "ola_enabled", nullable = false)
    @Builder.Default
    private Boolean olaEnabled = false;

    @Column(name = "ola_value")
    private Integer olaValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "ola_unit", nullable = false, length = 24)
    @Builder.Default
    private OlaUnit olaUnit = OlaUnit.HOURS;

    @Column(name = "ola_warning_percent", nullable = false)
    @Builder.Default
    private Integer olaWarningPercent = 80;

    @Column(name = "ola_use_working_time", nullable = false)
    @Builder.Default
    private Boolean olaUseWorkingTime = true;

    @Override
    public int compareTo(@NotNull SupportLine other) {
        Integer left = orderNumber == null ? Integer.MAX_VALUE : orderNumber;
        Integer right = other == null || other.orderNumber == null ? Integer.MAX_VALUE : other.orderNumber;
        int byOrder = left.compareTo(right);
        if (byOrder != 0) {
            return byOrder;
        }
        return String.valueOf(name).compareToIgnoreCase(other == null ? "" : String.valueOf(other.name));
    }
}
