package ru.ravel.ItDesk.models;

import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(length = 1024)
    private String description;

    private String status;

    private String priority;

//    @OneToOne(targetEntity = User.class, cascade = CascadeType.ALL)
    private String executor;

    @ElementCollection
    private List<String> tags;

    private boolean isCompleted;
}
