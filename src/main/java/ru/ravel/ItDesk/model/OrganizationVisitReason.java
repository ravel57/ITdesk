package ru.ravel.ItDesk.model;

import jakarta.persistence.*;
import lombok.*;
import org.jetbrains.annotations.NotNull;

@Entity
@Table(
        name = "organization_visit_reason",
        uniqueConstraints = @UniqueConstraint(name = "uk_organization_visit_reason_name", columnNames = "name")
)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OrganizationVisitReason implements Comparable<OrganizationVisitReason> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean urgent = false;

    @Builder.Default
    @Column(nullable = false, columnDefinition = "int default 0")
    private Integer orderNumber = 0;

    @Override
    public int compareTo(@NotNull OrganizationVisitReason other) {
        int thisOrder = orderNumber == null ? 0 : orderNumber;
        int otherOrder = other.orderNumber == null ? 0 : other.orderNumber;
        int orderCompare = Integer.compare(thisOrder, otherOrder);
        if (orderCompare != 0) {
            return orderCompare;
        }
        if (id == null && other.id == null) {
            return 0;
        }
        if (id == null) {
            return 1;
        }
        if (other.id == null) {
            return -1;
        }
        return id.compareTo(other.id);
    }
}
