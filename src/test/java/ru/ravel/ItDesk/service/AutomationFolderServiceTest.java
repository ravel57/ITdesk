package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.dto.AutomationFolderDtos.FolderRequest;
import ru.ravel.ItDesk.dto.AutomationFolderDtos.LayoutItem;
import ru.ravel.ItDesk.dto.AutomationFolderDtos.LayoutRequest;
import ru.ravel.ItDesk.model.AutomationFolder;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.repository.AutomationFolderRepository;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutomationFolderServiceTest {

	private AutomationFolderRepository folders;
	private AutomationTriggerRepository triggers;
	private AutomationFolderService service;
	private final List<AutomationFolder> folderRows = new ArrayList<>();
	private final List<AutomationTrigger> triggerRows = new ArrayList<>();


	@BeforeEach
	void setUp() {
		folders = mock(AutomationFolderRepository.class);
		triggers = mock(AutomationTriggerRepository.class);
		service = new AutomationFolderService(folders, triggers);
		when(folders.findAll()).thenAnswer(i -> new ArrayList<>(folderRows));
		when(triggers.findAll()).thenAnswer(i -> new ArrayList<>(triggerRows));
		when(folders.findById(anyLong())).thenAnswer(i -> folderRows.stream().filter(f -> f.getId().equals(i.getArgument(0))).findFirst());
		when(folders.save(any())).thenAnswer(i -> i.getArgument(0));
		for (long id = 1; id <= 4; id++) {
			triggerRows.add(AutomationTrigger.builder().id(id).orderNumber((int) id - 1).displayOrder((int) id - 1).build());
		}
		folderRows.add(AutomationFolder.builder().id(10L).name("A").displayOrder(-1).build());
	}


	@Test
	void layoutDerivesExecutionOrderFromReadingOrderAndNeverDropsUnmentionedRules() {
		// папка A: [3, 1]; затем правило 4. Правило 2 клиент не упомянул — оно не должно потеряться.
		service.saveLayout(new LayoutRequest(List.of(
				new LayoutItem("FOLDER", 10L, true, List.of(
						new LayoutItem("TRIGGER", 3L, null, null),
						new LayoutItem("TRIGGER", 1L, null, null))),
				new LayoutItem("TRIGGER", 4L, null, null)
		)));

		verify(triggers).updatePlacement(3L, 10L, 0, 0);
		verify(triggers).updatePlacement(1L, 10L, 1, 1);
		verify(triggers).updatePlacement(4L, null, 1, 2);
		verify(triggers).updatePlacement(2L, null, 2, 3);
		assertThat(folderRows.get(0).getCollapsed()).isTrue();
		assertThat(folderRows.get(0).getDisplayOrder()).isZero();
	}


	@Test
	void deletingFolderKeepsRulesAtItsPositionAndExecutionOrder() {
		triggerRows.get(2).setFolderId(10L);
		triggerRows.get(2).setDisplayOrder(0);
		triggerRows.get(3).setFolderId(10L);
		triggerRows.get(3).setDisplayOrder(1);
		// root: folder A(-1) [3,4], 1(0), 2(1) -> исходный порядок выполнения был 3,4,1,2 в чтении

		service.delete(10L);

		verify(triggers).updatePlacement(3L, null, 0, 0);
		verify(triggers).updatePlacement(4L, null, 1, 1);
		verify(triggers).updatePlacement(1L, null, 2, 2);
		verify(triggers).updatePlacement(2L, null, 3, 3);
		verify(folders).delete(folderRows.get(0));
	}


	@Test
	void nestedFoldersFlattenInReadingOrderAndKeepParent() {
		folderRows.add(AutomationFolder.builder().id(11L).name("B").displayOrder(5).build());
		// A [ 4, B [ 2 ], 1 ], затем 3
		service.saveLayout(new LayoutRequest(List.of(
				new LayoutItem("FOLDER", 10L, null, List.of(
						new LayoutItem("TRIGGER", 4L, null, null),
						new LayoutItem("FOLDER", 11L, null, List.of(new LayoutItem("TRIGGER", 2L, null, null))),
						new LayoutItem("TRIGGER", 1L, null, null))),
				new LayoutItem("TRIGGER", 3L, null, null)
		)));

		verify(triggers).updatePlacement(4L, 10L, 0, 0);
		verify(triggers).updatePlacement(2L, 11L, 0, 1);
		verify(triggers).updatePlacement(1L, 10L, 2, 2);
		verify(triggers).updatePlacement(3L, null, 1, 3);
		assertThat(folderRows.get(1).getParentId()).isEqualTo(10L);
		assertThat(folderRows.get(1).getDisplayOrder()).isEqualTo(1);
		assertThat(folderRows.get(0).getParentId()).isNull();
	}


	@Test
	void deletingFolderLiftsNestedFoldersToParent() {
		AutomationFolder inner = AutomationFolder.builder().id(11L).name("B").displayOrder(0).parentId(10L).build();
		folderRows.add(inner);
		triggerRows.get(0).setFolderId(11L); // 1 внутри B
		triggerRows.get(0).setDisplayOrder(0);

		service.delete(10L);

		assertThat(inner.getParentId()).isNull();
		verify(triggers).updatePlacement(1L, 11L, 0, 0);
		verify(triggers).updatePlacement(2L, null, 1, 1);
		verify(folders).delete(folderRows.get(0));
	}


	@Test
	void renameValidatesAndTrims() {
		assertThat(service.update(10L, new FolderRequest("  Новое  ", null)).name()).isEqualTo("Новое");
		assertThatThrownBy(() -> service.update(10L, new FolderRequest("   ", null))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.update(10L, new FolderRequest("x".repeat(256), null))).isInstanceOf(IllegalArgumentException.class);
	}
}
