package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationFolderDtos.*;
import ru.ravel.ItDesk.model.AutomationFolder;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.repository.AutomationFolderRepository;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;

import java.util.*;


/**
 * Папки автоматизаций (с произвольной вложенностью) — чисто организационная группировка.
 * <p>
 * Порядок выполнения правил задаёт {@code order_number} и читается движком как раньше. Он всегда равен
 * порядку «чтения» дерева сверху вниз (каждая папка раскрыта на месте, вложенные — рекурсивно). Поэтому создание,
 * переименование, сворачивание и удаление папки порядок выполнения не меняют; меняет его только явный перенос
 * правила или папки пользователем — так же, как раньше менял перетаскивание в плоском списке.
 */
@Service
@RequiredArgsConstructor
public class AutomationFolderService {

	private static final int MAX_NAME_LENGTH = 255;

	private final AutomationFolderRepository folderRepository;
	private final AutomationTriggerRepository triggerRepository;


	@Transactional(readOnly = true)
	public List<Folder> list() {
		return folderRepository.findAll().stream()
				.sorted(Comparator.comparing(AutomationFolder::getDisplayOrder).thenComparing(AutomationFolder::getId))
				.map(this::toDto)
				.toList();
	}


	/** Новая папка появляется в начале списка (пустая, поэтому порядок выполнения не затрагивает). */
	@Transactional
	public Folder create(FolderRequest request) {
		String name = normalizeName(request == null ? null : request.name());
		Integer minTriggers = triggerRepository.minRootDisplayOrder();
		Integer minFolders = folderRepository.minDisplayOrder();
		int top = Math.min(minTriggers == null ? 0 : minTriggers, minFolders == null ? 0 : minFolders) - 1;
		AutomationFolder saved = folderRepository.save(AutomationFolder.builder()
				.name(name)
				.collapsed(false)
				.displayOrder(top)
				.build());
		return toDto(saved);
	}


	@Transactional
	public Folder update(Long id, FolderRequest request) {
		AutomationFolder folder = folderRepository.findById(id).orElseThrow(() -> new NoSuchElementException("folder not found"));
		if (request != null && request.name() != null) {
			folder.setName(normalizeName(request.name()));
		}
		if (request != null && request.collapsed() != null) {
			folder.setCollapsed(request.collapsed());
		}
		return toDto(folderRepository.save(folder));
	}


	/** Содержимое удалённой папки (правила и вложенные папки) встаёт в родительский контейнер на её место. */
	@Transactional
	public LayoutResponse delete(Long id) {
		AutomationFolder folder = folderRepository.findById(id).orElseThrow(() -> new NoSuchElementException("folder not found"));
		apply(toItems(currentTree(), id), id);
		folderRepository.delete(folder);
		return response();
	}


	@Transactional
	public LayoutResponse saveLayout(LayoutRequest request) {
		apply(request == null || request.items() == null ? List.of() : request.items(), null);
		return response();
	}


	private void apply(List<LayoutItem> items, Long excludedFolderId) {
		Map<Long, AutomationFolder> folders = new LinkedHashMap<>();
		folderRepository.findAll().stream()
				.filter(f -> !f.getId().equals(excludedFolderId))
				.forEach(f -> folders.put(f.getId(), f));
		Map<Long, AutomationTrigger> triggers = new LinkedHashMap<>();
		triggerRepository.findAll().forEach(t -> triggers.put(t.getId(), t));

		Placement placement = new Placement(folders, triggers);
		List<Node> tree = placement.build(items);

		// Всё, что клиент не упомянул (например, создано другим пользователем параллельно), не теряем:
		// остаётся в своём прежнем контейнере, если тот есть в дереве, иначе уходит на верхний уровень.
		Comparator<AutomationFolder> foldersByPosition = Comparator
				.comparing((AutomationFolder f) -> Objects.requireNonNullElse(f.getDisplayOrder(), 0))
				.thenComparing(AutomationFolder::getId);
		List<AutomationFolder> pending = folders.values().stream()
				.filter(f -> !placement.placedFolders.containsKey(f.getId()))
				.sorted(foldersByPosition)
				.toList();
		boolean progress = true;
		List<AutomationFolder> left = new ArrayList<>(pending);
		while (progress && !left.isEmpty()) {
			progress = false;
			for (Iterator<AutomationFolder> it = left.iterator(); it.hasNext(); ) {
				AutomationFolder folder = it.next();
				Long parentId = folder.getParentId();
				if (parentId == null || !folders.containsKey(parentId)) {
					Node node = new Node(folder, null);
					placement.placedFolders.put(folder.getId(), node);
					tree.add(node);
				} else if (placement.placedFolders.containsKey(parentId)) {
					Node node = new Node(folder, null);
					placement.placedFolders.put(folder.getId(), node);
					placement.placedFolders.get(parentId).children.add(node);
				} else {
					continue;
				}
				it.remove();
				progress = true;
			}
		}
		for (AutomationFolder folder : left) { // циклы в старых данных — выносим наверх
			Node node = new Node(folder, null);
			placement.placedFolders.put(folder.getId(), node);
			tree.add(node);
		}

		Comparator<AutomationTrigger> byExecution = Comparator
				.comparing((AutomationTrigger t) -> Objects.requireNonNullElse(t.getOrderNumber(), 0))
				.thenComparing(AutomationTrigger::getId);
		Comparator<AutomationTrigger> byPosition = Comparator
				.comparing((AutomationTrigger t) -> Objects.requireNonNullElse(t.getDisplayOrder(), 0))
				.thenComparing(byExecution);
		triggers.values().stream()
				.filter(t -> !placement.placedTriggers.contains(t.getId()))
				.sorted(byPosition)
				.forEach(t -> {
					Node folderNode = t.getFolderId() == null ? null : placement.placedFolders.get(t.getFolderId());
					(folderNode == null ? tree : folderNode.children).add(new Node(null, t));
				});

		// Сначала папки (их правки сбрасываются перед массовыми обновлениями правил), затем правила.
		savePositions(tree, null);
		int[] executionOrder = {0};
		saveTriggers(tree, null, executionOrder);
	}


	private void savePositions(List<Node> nodes, Long parentId) {
		int position = 0;
		for (Node node : nodes) {
			node.position = position++;
			if (node.folder != null) {
				node.folder.setDisplayOrder(node.position);
				node.folder.setParentId(parentId);
				folderRepository.save(node.folder);
				savePositions(node.children, node.folder.getId());
			}
		}
	}


	private void saveTriggers(List<Node> nodes, Long folderId, int[] executionOrder) {
		for (Node node : nodes) {
			if (node.folder != null) {
				saveTriggers(node.children, node.folder.getId(), executionOrder);
			} else {
				triggerRepository.updatePlacement(node.trigger.getId(), folderId, node.position, executionOrder[0]++);
			}
		}
	}


	/** Текущее дерево из БД: в каждом контейнере папки и правила вперемешку по display_order. */
	private List<Node> currentTree() {
		List<AutomationFolder> folders = folderRepository.findAll();
		List<AutomationTrigger> triggers = triggerRepository.findAll();
		Map<Long, Node> nodes = new LinkedHashMap<>();
		folders.forEach(f -> nodes.put(f.getId(), new Node(f, null)));

		Map<Long, List<Node>> byContainer = new HashMap<>();
		List<Node> root = new ArrayList<>();
		List<Node> all = new ArrayList<>(nodes.values());
		triggers.forEach(t -> all.add(new Node(null, t)));
		for (Node node : all) {
			Long containerId = node.folder != null ? node.folder.getParentId() : node.trigger.getFolderId();
			if (containerId != null && nodes.containsKey(containerId) && !isCyclic(node, nodes)) {
				byContainer.computeIfAbsent(containerId, k -> new ArrayList<>()).add(node);
			} else {
				root.add(node);
			}
		}
		Comparator<Node> order = Comparator
				.comparing((Node n) -> n.folder != null
						? Objects.requireNonNullElse(n.folder.getDisplayOrder(), 0)
						: Objects.requireNonNullElse(n.trigger.getDisplayOrder(), 0))
				.thenComparing(n -> n.folder != null ? 0 : 1)
				.thenComparing(n -> n.folder != null ? n.folder.getId() : n.trigger.getId());
		root.sort(order);
		nodes.forEach((id, node) -> {
			List<Node> children = byContainer.getOrDefault(id, List.of());
			node.children.addAll(children);
			node.children.sort(order);
		});
		return root;
	}


	private boolean isCyclic(Node node, Map<Long, Node> nodes) {
		if (node.folder == null) return false;
		Set<Long> seen = new HashSet<>();
		Long current = node.folder.getId();
		while (current != null) {
			if (!seen.add(current)) return true;
			Node n = nodes.get(current);
			current = n == null ? null : n.folder.getParentId();
		}
		return false;
	}


	private List<LayoutItem> toItems(List<Node> nodes, Long liftedFolderId) {
		List<LayoutItem> items = new ArrayList<>();
		for (Node node : nodes) {
			if (node.folder == null) {
				items.add(new LayoutItem("TRIGGER", node.trigger.getId(), null, null));
			} else if (node.folder.getId().equals(liftedFolderId)) {
				items.addAll(toItems(node.children, liftedFolderId));
			} else {
				items.add(new LayoutItem("FOLDER", node.folder.getId(), null, toItems(node.children, liftedFolderId)));
			}
		}
		return items;
	}


	private LayoutResponse response() {
		triggerRepository.flush();
		return new LayoutResponse(
				list(),
				triggerRepository.findAll().stream()
						.map(t -> new TriggerPlacement(t.getId(), t.getFolderId(), t.getDisplayOrder(), t.getOrderNumber()))
						.toList()
		);
	}


	private String normalizeName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("name required");
		}
		String trimmed = name.trim();
		if (trimmed.length() > MAX_NAME_LENGTH) {
			throw new IllegalArgumentException("name too long");
		}
		return trimmed;
	}


	private Folder toDto(AutomationFolder folder) {
		return new Folder(folder.getId(), folder.getName(), Boolean.TRUE.equals(folder.getCollapsed()),
				folder.getDisplayOrder(), folder.getParentId());
	}


	/** Разбор присланного клиентом дерева: каждая папка и правило учитываются один раз, неизвестные id игнорируются. */
	private static final class Placement {
		final Map<Long, AutomationFolder> folders;
		final Map<Long, AutomationTrigger> triggers;
		final Map<Long, Node> placedFolders = new HashMap<>();
		final Set<Long> placedTriggers = new HashSet<>();

		Placement(Map<Long, AutomationFolder> folders, Map<Long, AutomationTrigger> triggers) {
			this.folders = folders;
			this.triggers = triggers;
		}

		List<Node> build(List<LayoutItem> items) {
			List<Node> result = new ArrayList<>();
			for (LayoutItem item : items) {
				if (item == null || item.id() == null) continue;
				if ("FOLDER".equalsIgnoreCase(item.type())) {
					AutomationFolder folder = folders.get(item.id());
					if (folder == null || placedFolders.containsKey(folder.getId())) continue;
					if (item.collapsed() != null) folder.setCollapsed(item.collapsed());
					Node node = new Node(folder, null);
					placedFolders.put(folder.getId(), node);
					node.children.addAll(build(item.items() == null ? List.of() : item.items()));
					result.add(node);
				} else {
					AutomationTrigger trigger = triggers.get(item.id());
					if (trigger != null && placedTriggers.add(trigger.getId())) result.add(new Node(null, trigger));
				}
			}
			return result;
		}
	}


	private static final class Node {
		final AutomationFolder folder;
		final AutomationTrigger trigger;
		final List<Node> children = new ArrayList<>();
		int position;

		Node(AutomationFolder folder, AutomationTrigger trigger) {
			this.folder = folder;
			this.trigger = trigger;
		}
	}
}
