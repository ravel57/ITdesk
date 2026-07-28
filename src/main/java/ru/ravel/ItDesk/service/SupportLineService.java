package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.SupportLineRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Service
@RequiredArgsConstructor
public class SupportLineService {

    private final SupportLineRepository supportLineRepository;
    private final UserRepository userRepository;
    private final TaskRepository taskRepository;

    @Transactional(readOnly = true)
    public List<SupportLine> getSupportLines() {
        return supportLineRepository.findAll().stream().sorted().toList();
    }

    @Transactional
    public SupportLine create(SupportLine request) {
        validate(request, null);
        SupportLine line = SupportLine.builder()
                .name(request.getName().trim())
                .description(normalizeDescription(request.getDescription()))
                .level(normalizeLevel(request.getLevel()))
                .active(Boolean.TRUE.equals(request.getDefaultSelection()) || !Boolean.FALSE.equals(request.getActive()))
                .defaultSelection(Boolean.TRUE.equals(request.getDefaultSelection()))
                .orderNumber((int) supportLineRepository.count())
                .members(resolveMembers(request.getMembers()))
                .build();
        if (Boolean.TRUE.equals(line.getDefaultSelection())) {
            clearDefaultSelection(null);
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
        line.setName(request.getName().trim());
        line.setDescription(normalizeDescription(request.getDescription()));
        line.setLevel(normalizeLevel(request.getLevel()));
        line.setActive(!Boolean.FALSE.equals(request.getActive()));
        line.setMembers(resolveMembers(request.getMembers()));
        if (request.getOrderNumber() != null) {
            line.setOrderNumber(request.getOrderNumber());
        }
        if (Boolean.TRUE.equals(request.getDefaultSelection())) {
            clearDefaultSelection(line.getId());
            line.setDefaultSelection(true);
        } else {
            line.setDefaultSelection(false);
        }
        return supportLineRepository.save(line);
    }

    @Transactional
    public void delete(Long id) {
        if (id == null) {
            return;
        }
        if (taskRepository.existsBySupportLineId(id)) {
            throw new IllegalStateException("Нельзя удалить линию, используемую в заявках");
        }
        supportLineRepository.deleteById(id);
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

    private void validate(SupportLine request, Long currentId) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Название линии обязательно");
        }
        supportLineRepository.findByName(request.getName().trim())
                .filter(existing -> !Objects.equals(existing.getId(), currentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Линия с таким названием уже существует");
                });
    }

    private List<User> resolveMembers(List<User> requestedMembers) {
        if (requestedMembers == null || requestedMembers.isEmpty()) {
            return new ArrayList<>();
        }
        List<Long> ids = requestedMembers.stream()
                .filter(Objects::nonNull)
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new ArrayList<>(userRepository.findAllById(ids));
    }

    private void clearDefaultSelection(Long exceptId) {
        List<SupportLine> changed = supportLineRepository.findAll().stream()
                .filter(line -> !Objects.equals(line.getId(), exceptId))
                .peek(line -> line.setDefaultSelection(false))
                .toList();
        supportLineRepository.saveAll(changed);
    }

    private String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    private Integer normalizeLevel(Integer level) {
        return level == null || level < 1 ? 1 : level;
    }
}
