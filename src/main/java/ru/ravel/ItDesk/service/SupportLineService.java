package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.SupportLineRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SupportLineService {

    private final SupportLineRepository supportLineRepository;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final OlaService olaService;
    private final UserService userService;
    private final TaskRoutingService taskRoutingService;

    @Transactional(readOnly = true)
    public List<SupportLine> getSupportLines() {
        return supportLineRepository.findAll().stream().sorted().toList();
    }

    @Transactional
    public SupportLine create(SupportLine request) {
        validate(request, null);
        SupportLine line = new SupportLine();
        line.setOrderNumber(request.getOrderNumber() == null ? (int) supportLineRepository.count() : request.getOrderNumber());
        apply(line, request);
        if (Boolean.TRUE.equals(line.getDefaultSelection())) {
            clearDefaultSelection(null);
            line.setActive(true);
        }
        return supportLineRepository.save(line);
    }

    @Transactional
    public SupportLine update(SupportLine request) {
        if (request == null || request.getId() == null) {
            throw new IllegalArgumentException("supportLine.id is required");
        }
        validate(request, request.getId());
        SupportLine line = supportLineRepository.findById(request.getId()).orElseThrow();
        OlaConfiguration oldOla = OlaConfiguration.from(line);
        RoutingConfiguration oldRouting = RoutingConfiguration.from(line);
        apply(line, request);
        if (Boolean.TRUE.equals(line.getDefaultSelection())) {
            clearDefaultSelection(line.getId());
            line.setActive(true);
        }
        SupportLine saved = supportLineRepository.save(line);
        if (!oldOla.equals(OlaConfiguration.from(saved))) {
            reconfigureOpenTasksOla(saved);
        }
        if (!oldRouting.equals(RoutingConfiguration.from(saved))) {
            taskRoutingService.reconcileOpenTaskAssignments(saved, "Изменены настройки команды линии");
        }
        ensureDefaultLineExists();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        if (id == null) {
            return;
        }
        SupportLine line = supportLineRepository.findById(id).orElseThrow();
        if (taskRepository.existsBySupportLineId(id)) {
            throw new IllegalStateException("Нельзя удалить линию, используемую в заявках. Сделайте её неактивной.");
        }
        supportLineRepository.delete(line);
        ensureDefaultLineExists();
    }

    @Transactional
    public SupportLine setDefault(SupportLine request) {
        if (request == null || request.getId() == null) {
            throw new IllegalArgumentException("supportLine.id is required");
        }
        SupportLine line = supportLineRepository.findById(request.getId()).orElseThrow();
        clearDefaultSelection(line.getId());
        line.setDefaultSelection(true);
        line.setActive(true);
        return supportLineRepository.save(line);
    }

    @Transactional
    public List<SupportLine> resort(List<SupportLine> ordered) {
        if (ordered == null) {
            return getSupportLines();
        }
        for (int i = 0; i < ordered.size(); i++) {
            SupportLine row = ordered.get(i);
            if (row == null || row.getId() == null) {
                continue;
            }
            SupportLine line = supportLineRepository.findById(row.getId()).orElseThrow();
            line.setOrderNumber(i);
            supportLineRepository.save(line);
        }
        return getSupportLines();
    }

    private void apply(SupportLine line, SupportLine request) {
        List<User> members = resolveUsers(request.getMembers());
        User responsible = resolveUser(request.getResponsible());
        if (responsible != null && members.stream().noneMatch(user -> Objects.equals(user.getId(), responsible.getId()))) {
            members.add(responsible);
        }
        Set<Long> memberIds = members.stream().map(User::getId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<User> observers = new LinkedHashSet<>(resolveUsers(request.getObservers()));
        observers.removeIf(user -> user == null || memberIds.contains(user.getId()));

        line.setName(request.getName().trim());
        line.setDescription(normalizeDescription(request.getDescription()));
        line.setLevel(normalizeLevel(request.getLevel()));
        line.setActive(!Boolean.FALSE.equals(request.getActive()));
        line.setDefaultSelection(Boolean.TRUE.equals(request.getDefaultSelection()));
        if (request.getOrderNumber() != null) {
            line.setOrderNumber(request.getOrderNumber());
        }
        line.setResponsible(responsible);
        line.setMembers(members);
        line.setObservers(observers);
        line.setAssignmentStrategy(Objects.requireNonNullElse(request.getAssignmentStrategy(), SupportLineAssignmentStrategy.KEEP_UNASSIGNED));
        line.setVisibilityMode(Objects.requireNonNullElse(request.getVisibilityMode(), SupportLineVisibilityMode.INHERIT));
        line.setAllowSelfAssignment(!Boolean.FALSE.equals(request.getAllowSelfAssignment()));
        line.setNotifyOnNewTask(!Boolean.FALSE.equals(request.getNotifyOnNewTask()));
        line.setCapacityPerMember(Math.max(0, Objects.requireNonNullElse(request.getCapacityPerMember(), 0)));
        line.setOlaEnabled(Boolean.TRUE.equals(request.getOlaEnabled()));
        line.setOlaValue(Boolean.TRUE.equals(request.getOlaEnabled()) ? Math.max(1, Objects.requireNonNullElse(request.getOlaValue(), 1)) : null);
        line.setOlaUnit(Objects.requireNonNullElse(request.getOlaUnit(), OlaUnit.HOURS));
        line.setOlaWarningPercent(Math.max(1, Math.min(100, Objects.requireNonNullElse(request.getOlaWarningPercent(), 80))));
        line.setOlaUseWorkingTime(!Boolean.FALSE.equals(request.getOlaUseWorkingTime()));
    }

    private void validate(SupportLine request, Long currentId) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Название линии обязательно");
        }
        supportLineRepository.findByNameIgnoreCase(request.getName().trim())
                .filter(existing -> !Objects.equals(existing.getId(), currentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Линия с таким названием уже существует");
                });
        if (Boolean.TRUE.equals(request.getOlaEnabled()) && Objects.requireNonNullElse(request.getOlaValue(), 0) <= 0) {
            throw new IllegalArgumentException("Срок OLA должен быть больше нуля");
        }
    }

    private User resolveUser(User requested) {
        if (requested == null || requested.getId() == null) {
            return null;
        }
        return userRepository.findById(requested.getId())
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + requested.getId()));
    }

    private List<User> resolveUsers(Collection<User> requestedUsers) {
        if (requestedUsers == null || requestedUsers.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = requestedUsers.stream()
                .filter(Objects::nonNull)
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> users = userRepository.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, user -> user));
        return ids.stream().map(users::get).filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void reconfigureOpenTasksOla(SupportLine line) {
        if (line == null || line.getId() == null) {
            return;
        }
        User actor;
        try {
            actor = userService.getCurrentUser();
        } catch (Exception ignored) {
            actor = null;
        }
        List<Task> tasks = taskRepository.findOpenBySupportLineId(line.getId());
        for (Task task : tasks) {
            olaService.restart(task, actor, "Изменены настройки OLA линии");
        }
        taskRepository.saveAll(tasks);
    }


    private record RoutingConfiguration(
            Set<Long> memberIds,
            SupportLineAssignmentStrategy assignmentStrategy,
            Integer capacityPerMember
    ) {
        private static RoutingConfiguration from(SupportLine line) {
            Set<Long> ids = line == null || line.getMembers() == null
                    ? Set.of()
                    : line.getMembers().stream()
                            .filter(Objects::nonNull)
                            .map(User::getId)
                            .filter(Objects::nonNull)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return new RoutingConfiguration(
                    ids,
                    line == null
                            ? SupportLineAssignmentStrategy.KEEP_UNASSIGNED
                            : Objects.requireNonNullElse(line.getAssignmentStrategy(), SupportLineAssignmentStrategy.KEEP_UNASSIGNED),
                    line == null ? 0 : Math.max(0, Objects.requireNonNullElse(line.getCapacityPerMember(), 0))
            );
        }
    }


    private record OlaConfiguration(
            Boolean enabled,
            Integer value,
            OlaUnit unit,
            Integer warningPercent,
            Boolean useWorkingTime
    ) {
        private static OlaConfiguration from(SupportLine line) {
            if (line == null) {
                return new OlaConfiguration(false, null, OlaUnit.HOURS, 80, true);
            }
            return new OlaConfiguration(
                    Boolean.TRUE.equals(line.getOlaEnabled()),
                    line.getOlaValue(),
                    Objects.requireNonNullElse(line.getOlaUnit(), OlaUnit.HOURS),
                    Objects.requireNonNullElse(line.getOlaWarningPercent(), 80),
                    !Boolean.FALSE.equals(line.getOlaUseWorkingTime())
            );
        }
    }


    private void clearDefaultSelection(Long exceptId) {
        List<SupportLine> changed = supportLineRepository.findAll().stream()
                .filter(line -> !Objects.equals(line.getId(), exceptId))
                .peek(line -> line.setDefaultSelection(false))
                .toList();
        supportLineRepository.saveAll(changed);
    }

    private void ensureDefaultLineExists() {
        if (supportLineRepository.findByDefaultSelectionTrue().isPresent()) {
            return;
        }
        supportLineRepository.findFirstByActiveTrueOrderByOrderNumberAsc().ifPresent(line -> {
            line.setDefaultSelection(true);
            supportLineRepository.save(line);
        });
    }

    private String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    private Integer normalizeLevel(Integer level) {
        return level == null || level < 1 ? 1 : level;
    }
}
