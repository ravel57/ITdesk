package ru.ravel.ItDesk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.jetbrains.annotations.NotNull;
import ru.ravel.ItDesk.config.ChecklistItemListDeserializer;


@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TaskType implements Comparable<TaskType> {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@EqualsAndHashCode.Include
	private Long id;

	private String type;

	@Builder.Default
	@Column(nullable = false, columnDefinition = "boolean default false")
	private Boolean defaultSelection = false;

	@Column(nullable = false, columnDefinition = "int default 0")
	private Integer orderNumber;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	@JsonDeserialize(using = ChecklistItemListDeserializer.class)
	@Builder.Default
	private List<ChecklistItem> checklistTemplate = new ArrayList<>();

	@Builder.Default
	private Boolean autoApplyChecklist = true;


	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public TaskType(String type) {
		this.type = type == null ? null : type.trim();
		this.checklistTemplate = new ArrayList<>();
		this.autoApplyChecklist = true;
	}

	@Override
	public int compareTo(@NotNull TaskType o) {
		return orderNumber.compareTo(o.orderNumber);
	}
}