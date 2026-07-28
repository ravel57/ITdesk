package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;


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

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "support_line_members",
            joinColumns = @JoinColumn(name = "support_line_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @Builder.Default
    private List<User> members = new ArrayList<>();

    @Override
    public int compareTo(@NotNull SupportLine other) {
        Integer left = orderNumber == null ? Integer.MAX_VALUE : orderNumber;
        Integer right = other == null || other.orderNumber == null ? Integer.MAX_VALUE : other.orderNumber;
        return left.compareTo(right);
    }
}
