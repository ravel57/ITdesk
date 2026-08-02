package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.SupportLineRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.ZonedDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TaskRoutingService {

    private final TaskRepository taskRepository;
    private final ClientRepository clientRepository;
    private final SupportLineRepository supportLineRepository;
    private final UserRepository userRepository;
    private final OlaService olaService;
    private final EventPublisher eventPublisher;
    private final UserNotificationService userNotificationService;

    public void prepareNewTask(Task task) {
        if (task == null) {
            return;
        }
        SupportLine line = resolveSupportLine(task.getSupportLine(), true, true);
        User executor = resolveUser(task.getExecutor());
        if (executor != null) {
            validateExecutor(line, executor);
        } else {
            executor = chooseAutomaticExecutor(line, Objects.requireNonNullElse(line == null ? null : line.getAssignmentStrategy(), SupportLineAssignmentStrategy.KEEP_UNASSIGNED));
        }
        task.setSupportLine(line);
        task.setExecutor(executor);
        olaService.initializeFields(task, line, Objects.requireNonNullElse(task.getCreatedAt(), ZonedDateTime.now()));
    }

    @Transactional
    public void afterTaskCreated(Task task, User actor, String reason) {
        olaService.createStage(task, actor, reason);
        olaService.synchronizeWithTaskState(task);
        notifyLineMembers(task);
    }

    public RoutingResult normalizeUpdateRequest(Task existing, Task request) {
        if (existing == null || request == null) {
            throw new IllegalArgumentException("Заявка не передана");
        }
        SupportLine targetLine;
        if (request.getSupportLine() == null || sameEntity(existing.getSupportLine(), request.getSupportLine())) {
            // Существующую неактивную линию можно сохранить при обычном редактировании заявки.
            // Переходить на другую неактивную линию по-прежнему нельзя.
            targetLine = existing.getSupportLine();
        } else {
            targetLine = resolveSupportLine(request.getSupportLine(), false, true);
        }
        User targetExecutor = resolveUser(request.getExecutor());
        boolean lineChanged = !sameEntity(existing.getSupportLine(), targetLine);

        if (lineChanged && targetExecutor != null && sameEntity(targetExecutor, existing.getExecutor())
                && !isActiveMember(targetLine, targetExecutor)) {
            // UI обычно очищает исполнителя при смене линии. Эта проверка защищает и
            // старые клиенты, которые присылают прежнего исполнителя вместе со всей заявкой.
            targetExecutor = null;
        }

        if (targetExecutor != null) {
            validateExecutor(targetLine, targetExecutor);
            User actor = currentActorOrNull();
            boolean selfAssignment = actor != null && sameEntity(actor, targetExecutor)
                    && !sameEntity(existing.getExecutor(), targetExecutor);
            if (selfAssignment && targetLine != null
                    && Boolean.FALSE.equals(targetLine.getAllowSelfAssignment())
                    && !isAdmin(actor)) {
                throw new IllegalArgumentException("Самостоятельное назначение в линии «" + targetLine.getName() + "» запрещено");
            }
        } else if (lineChanged) {
            targetExecutor = chooseAutomaticExecutor(
                    targetLine,
                    Objects.requireNonNullElse(targetLine == null ? null : targetLine.getAssignmentStrategy(), SupportLineAssignmentStrategy.KEEP_UNASSIGNED)
            );
        }

        request.setSupportLine(targetLine);
        request.setExecutor(targetExecutor);
        return new RoutingResult(existing.getSupportLine(), targetLine, existing.getExecutor(), targetExecutor, lineChanged);
    }

    @Transactional
    public void applyNormalizedUpdate(Task task, RoutingResult result, User actor, String reason) {
        if (task == null || result == null) {
            return;
        }
        if (result.lineChanged()) {
            olaService.changeLine(task, result.newLine(), actor, reason);
        } else {
            task.setSupportLine(result.newLine());
        }
        task.setExecutor(result.newExecutor());
        if (result.lineChanged()) {
            notifyLineMembers(task);
        }
    }

    @Transactional
    public Task routeExistingTask(Long taskId, Long supportLineId, boolean assignLeastLoaded, String reason) {
        if (taskId == null || supportLineId == null) {
            throw new IllegalArgumentException("taskId и supportLineId обязательны");
        }
        Task task = taskRepository.findById(taskId).orElseThrow();
        SupportLine oldLine = task.getSupportLine();
        User oldExecutor = task.getExecutor();
        SupportLine newLine = resolveSupportLine(SupportLine.builder().id(supportLineId).build(), false, true);
        User newExecutor = assignLeastLoaded
                ? chooseLeastLoaded(newLine)
                : (isActiveMember(newLine, oldExecutor)
                        ? oldExecutor
                        : chooseAutomaticExecutor(
                                newLine,
                                Objects.requireNonNullElse(newLine.getAssignmentStrategy(), SupportLineAssignmentStrategy.KEEP_UNASSIGNED)
                        ));

        User actor = currentActorOrNull();
        if (!sameEntity(oldLine, newLine)) {
            olaService.changeLine(task, newLine, actor, reason);
        } else {
            task.setSupportLine(newLine);
        }
        task.setExecutor(newExecutor);
        if (!sameEntity(oldLine, newLine)) {
            olaService.synchronizeWithTaskState(task);
        }
        task.setLastActivity(ZonedDateTime.now());
        Task saved = taskRepository.save(task);
        Client client = clientRepository.findByTaskId(taskId).orElse(null);

        if (!sameEntity(oldLine, newLine)) {
            notifyLineMembers(saved);
            eventPublisher.publish(TriggerType.TASK_GROUP_CHANGED, eventPayload(
                    "task", saved,
                    "client", client,
                    "oldSupportLine", oldLine,
                    "newSupportLine", newLine
            ));
        }
        if (!sameEntity(oldExecutor, newExecutor)) {
            eventPublisher.publish(TriggerType.TASK_ASSIGNEE_CHANGED, eventPayload(
                    "task", saved,
                    "client", client,
                    "oldExecutor", oldExecutor,
                    "newExecutor", newExecutor
            ));
            notifyAssigned(saved);
        }
        return olaService.enrich(saved);
    }

    @Transactional
    public void reconcileOpenTaskAssignments(SupportLine line, String reason) {
        if (line == null || line.getId() == null) {
            return;
        }
        for (Task task : taskRepository.findOpenBySupportLineId(line.getId())) {
            User oldExecutor = task.getExecutor();
            User newExecutor = isActiveMember(line, oldExecutor)
                    ? oldExecutor
                    : chooseAutomaticExecutor(
                            line,
                            Objects.requireNonNullElse(line.getAssignmentStrategy(), SupportLineAssignmentStrategy.KEEP_UNASSIGNED)
                    );
            if (sameEntity(oldExecutor, newExecutor)) {
                continue;
            }
            task.setExecutor(newExecutor);
            task.setLastActivity(ZonedDateTime.now());
            Task saved = taskRepository.save(task);
            Client client = clientRepository.findByTaskId(task.getId()).orElse(null);
            eventPublisher.publish(TriggerType.TASK_ASSIGNEE_CHANGED, eventPayload(
                    "task", saved,
                    "client", client,
                    "oldExecutor", oldExecutor,
                    "newExecutor", newExecutor,
                    "reason", reason
            ));
            notifyAssigned(saved);
        }
    }


    public SupportLine resolveSupportLine(SupportLine requested, boolean useDefault, boolean requireActive) {
        SupportLine line = null;
        if (requested != null && requested.getId() != null) {
            line = supportLineRepository.findById(requested.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Линия поддержки не найдена: " + requested.getId()));
        } else if (requested != null && requested.getName() != null && !requested.getName().isBlank()) {
            line = supportLineRepository.findByName(requested.getName().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Линия поддержки не найдена: " + requested.getName()));
        } else if (useDefault) {
            line = supportLineRepository.findByDefaultSelectionTrue()
                    .filter(item -> !Boolean.FALSE.equals(item.getActive()))
                    .or(() -> supportLineRepository.findFirstByActiveTrueOrderByOrderNumberAsc())
                    .orElse(null);
        }
        if (requireActive && line != null && Boolean.FALSE.equals(line.getActive())) {
            throw new IllegalArgumentException("Линия поддержки неактивна: " + line.getName());
        }
        return line;
    }

    public User resolveUser(User requested) {
        if (requested == null || requested.getId() == null) {
            return null;
        }
        return userRepository.findById(requested.getId())
                .orElseThrow(() -> new IllegalArgumentException("Исполнитель не найден: " + requested.getId()));
    }

    public void validateExecutor(SupportLine line, User executor) {
        if (executor == null) {
            return;
        }
        if (!executor.isEnabled()) {
            throw new IllegalArgumentException("Нельзя назначить неактивного исполнителя");
        }
        if (line == null) {
            throw new IllegalArgumentException("Сначала выберите линию поддержки");
        }
        if (!isActiveMember(line, executor)) {
            throw new IllegalArgumentException("Исполнитель не является активным участником линии «" + line.getName() + "»");
        }
    }

    public boolean isActiveMember(SupportLine line, User user) {
        if (line == null || user == null || user.getId() == null || !user.isEnabled()) {
            return false;
        }
        return safeMembers(line).stream().anyMatch(member -> sameEntity(member, user));
    }

    public User chooseLeastLoaded(SupportLine line) {
        if (line == null) {
            return null;
        }
        return safeMembers(line).stream()
                .filter(Objects::nonNull)
                .filter(User::isEnabled)
                .filter(user -> user.getId() != null)
                .filter(user -> isUnderCapacity(line, user))
                .min(Comparator
                        .comparingLong((User user) -> taskRepository.countOpenByExecutorId(user.getId()))
                        .thenComparing(User::getId))
                .orElse(null);
    }

    private User chooseAutomaticExecutor(SupportLine line, SupportLineAssignmentStrategy strategy) {
        if (line == null) {
            return null;
        }
        return switch (strategy) {
            case LEAST_LOADED -> chooseLeastLoaded(line);
            case ROUND_ROBIN -> chooseRoundRobin(line);
            case KEEP_UNASSIGNED, MANUAL -> null;
        };
    }

    private User chooseRoundRobin(SupportLine line) {
        List<User> members = safeMembers(line).stream()
                .filter(Objects::nonNull)
                .filter(User::isEnabled)
                .filter(user -> user.getId() != null)
                .filter(user -> isUnderCapacity(line, user))
                .sorted(Comparator.comparing(User::getId))
                .toList();
        if (members.isEmpty()) {
            return null;
        }
        Long cursor = line.getRoundRobinCursor();
        User selected = members.stream()
                .filter(user -> cursor == null || user.getId() > cursor)
                .findFirst()
                .orElse(members.getFirst());
        line.setRoundRobinCursor(selected.getId());
        supportLineRepository.save(line);
        return selected;
    }

    private boolean isUnderCapacity(SupportLine line, User user) {
        if (line == null || user == null || user.getId() == null) {
            return false;
        }
        int capacity = Math.max(0, Objects.requireNonNullElse(line.getCapacityPerMember(), 0));
        return capacity == 0 || taskRepository.countOpenByExecutorId(user.getId()) < capacity;
    }


    private List<User> safeMembers(SupportLine line) {
        if (line == null || line.getMembers() == null) {
            return List.of();
        }
        return line.getMembers();
    }

    private User currentActorOrNull() {
        try {
            return userRepository.findByUsername(org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName()).orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void notifyLineMembers(Task task) {
        SupportLine line = task == null ? null : task.getSupportLine();
        if (line == null || !Boolean.TRUE.equals(line.getNotifyOnNewTask()) || task.getExecutor() != null) {
            return;
        }
        LinkedHashSet<User> recipients = new LinkedHashSet<>(safeMembers(line));
        if (line.getResponsible() != null) {
            recipients.add(line.getResponsible());
        }
        recipients.stream()
                .filter(Objects::nonNull)
                .filter(User::isEnabled)
                .filter(user -> task.getExecutor() == null || !sameEntity(user, task.getExecutor()))
                .filter(user -> user.getId() != null)
                .forEach(user -> userNotificationService.send(new UserNotification(
                        UserNotificationEvent.NEW_TASK,
                        "Новая заявка в линии «%s»: %s".formatted(line.getName(), Objects.toString(task.getName(), "")),
                        user.getId()
                )));
    }


    private boolean isAdmin(User user) {
        return user != null && user.getAuthorities() != null && user.getAuthorities().stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .anyMatch(authority -> authority.contains("ADMIN"));
    }


    private void notifyAssigned(Task task) {
        if (task != null && task.getExecutor() != null) {
            userNotificationService.send(new UserNotification(
                    UserNotificationEvent.NEW_TASK,
                    Objects.toString(task.getName(), ""),
                    task.getExecutor().getId()
            ));
        }
    }

    private static boolean sameEntity(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        try {
            Object leftId = left.getClass().getMethod("getId").invoke(left);
            Object rightId = right.getClass().getMethod("getId").invoke(right);
            return Objects.equals(leftId, rightId);
        } catch (Exception ignored) {
            return Objects.equals(left, right);
        }
    }

    private static Map<String, Object> eventPayload(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    public record RoutingResult(
            SupportLine oldLine,
            SupportLine newLine,
            User oldExecutor,
            User newExecutor,
            boolean lineChanged
    ) {
    }
}
