package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.OrganizationVisitReason;
import ru.ravel.ItDesk.repository.OrganizationVisitReasonRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrganizationVisitReasonService {

    private final OrganizationVisitReasonRepository organizationVisitReasonRepository;

    public List<OrganizationVisitReason> getReasons() {
        return organizationVisitReasonRepository.findAllByOrderByOrderNumberAscIdAsc();
    }

    @Transactional
    public OrganizationVisitReason newReason(@NotNull OrganizationVisitReason reason) {
        String name = normalizeName(reason.getName());
        if (organizationVisitReasonRepository.existsByNameIgnoreCase(name)) {
            throw new IllegalArgumentException("Причина выезда с таким названием уже существует");
        }
        reason.setId(null);
        reason.setName(name);
        reason.setUrgent(Boolean.TRUE.equals(reason.getUrgent()));
        reason.setOrderNumber(getReasons().size());
        return organizationVisitReasonRepository.save(reason);
    }

    @Transactional
    public OrganizationVisitReason updateReason(@NotNull OrganizationVisitReason reason) {
        if (reason.getId() == null) {
            throw new IllegalArgumentException("Не указан id причины выезда");
        }
        OrganizationVisitReason existing = organizationVisitReasonRepository.findById(reason.getId()).orElseThrow();
        String name = normalizeName(reason.getName());
        if (organizationVisitReasonRepository.existsByNameIgnoreCaseAndIdNot(name, existing.getId())) {
            throw new IllegalArgumentException("Причина выезда с таким названием уже существует");
        }
        existing.setName(name);
        existing.setUrgent(Boolean.TRUE.equals(reason.getUrgent()));
        if (reason.getOrderNumber() != null) {
            existing.setOrderNumber(reason.getOrderNumber());
        }
        return organizationVisitReasonRepository.save(existing);
    }

    @Transactional
    public void deleteReason(Long reasonId) {
        organizationVisitReasonRepository.deleteById(reasonId);
        List<OrganizationVisitReason> remaining = getReasons();
        for (int index = 0; index < remaining.size(); index++) {
            remaining.get(index).setOrderNumber(index);
        }
        organizationVisitReasonRepository.saveAll(remaining);
    }

    @Transactional
    public List<OrganizationVisitReason> resortReasons(@NotNull List<OrganizationVisitReason> orderedReasons) {
        Map<Long, Integer> positions = new HashMap<>();
        for (int index = 0; index < orderedReasons.size(); index++) {
            OrganizationVisitReason reason = orderedReasons.get(index);
            if (reason != null && reason.getId() != null) {
                positions.put(reason.getId(), index);
            }
        }

        List<OrganizationVisitReason> persisted = organizationVisitReasonRepository.findAll();
        for (OrganizationVisitReason reason : persisted) {
            Integer position = positions.get(reason.getId());
            if (position != null) {
                reason.setOrderNumber(position);
            }
        }
        organizationVisitReasonRepository.saveAll(persisted);
        return getReasons();
    }

    public String resolveReasonName(String requestedName) {
        List<OrganizationVisitReason> reasons = getReasons();
        if (reasons.isEmpty()) {
            throw new IllegalStateException("Не настроены причины выездов");
        }

        if (requestedName == null || requestedName.isBlank()) {
            return reasons.getFirst().getName();
        }

        String normalized = requestedName.trim();
        return organizationVisitReasonRepository.findFirstByNameIgnoreCase(normalized)
                .map(OrganizationVisitReason::getName)
                .orElseThrow(() -> new IllegalArgumentException("Неизвестная причина выезда: " + normalized));
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Название причины выезда обязательно");
        }
        return normalized;
    }
}
