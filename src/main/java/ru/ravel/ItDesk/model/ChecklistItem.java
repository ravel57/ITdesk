package ru.ravel.ItDesk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChecklistItem {
	private String id;

	private String text;

	@Builder.Default
	private Boolean completed = false;
}
