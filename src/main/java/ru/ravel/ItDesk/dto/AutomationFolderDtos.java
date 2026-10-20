package ru.ravel.ItDesk.dto;

import java.util.List;


/** Контракты API папок автоматизаций. Бэкенд хранит только «что в чём лежит и в каком порядке», внешний вид — на фронте. */
public final class AutomationFolderDtos {

	private AutomationFolderDtos() {
	}


	public record Folder(Long id, String name, Boolean collapsed, Integer displayOrder, Long parentId) {
	}


	public record FolderRequest(String name, Boolean collapsed) {
	}


	/**
	 * Элемент дерева: {@code type} = FOLDER (с {@code items} — содержимым по порядку, включая вложенные папки)
	 * или TRIGGER (правило). Порядок элементов в списке — порядок отображения.
	 */
	public record LayoutItem(String type, Long id, Boolean collapsed, List<LayoutItem> items) {
	}


	public record LayoutRequest(List<LayoutItem> items) {
	}


	public record TriggerPlacement(Long id, Long folderId, Integer displayOrder, Integer orderNumber) {
	}


	public record LayoutResponse(List<Folder> folders, List<TriggerPlacement> triggers) {
	}
}
