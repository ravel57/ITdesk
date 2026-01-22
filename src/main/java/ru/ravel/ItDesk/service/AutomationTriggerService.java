package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationTriggerDto;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AutomationTriggerService {

	private final AutomationTriggerRepository repository;


	@Transactional(readOnly = true)
	public List<AutomationTriggerDto> list() {
		return repository.findAll().stream().sorted().map(this::toDto).toList();
	}


	@Transactional
	public AutomationTriggerDto create(AutomationTriggerDto dto) {
		validate(dto);
		int nextOrder = repository.findAll().size();
		AutomationTrigger e = AutomationTrigger.builder()
				.name(dto.getName())
				.description(dto.getDescription())
				.triggerType(TriggerType.valueOf(dto.getTriggerType()))
				.expression(dto.getExpression())
				.action(dto.getAction())
				.orderNumber(nextOrder)
				.build();
		return toDto(repository.save(e));
	}


	@Transactional
	public AutomationTriggerDto update(AutomationTriggerDto dto) {
		if (dto.getId() == null) {
			throw new IllegalArgumentException("id is required");
		}
		validate(dto);
		AutomationTrigger e = repository.findById(dto.getId()).orElseThrow();
		e.setName(dto.getName());
		e.setDescription(dto.getDescription());
		e.setTriggerType(TriggerType.valueOf(dto.getTriggerType()));
		e.setExpression(dto.getExpression());
		e.setAction(dto.getAction());
		// orderNumber сохраняем как есть (или можно принимать от клиента)
		if (dto.getOrderNumber() != null) {
			e.setOrderNumber(dto.getOrderNumber());
		}
		return toDto(repository.save(e));
	}


	@Transactional
	public void delete(Long id) {
		repository.deleteById(id);
	}


	/**
	 * UI шлёт весь список после drag&drop.
	 * Переустанавливаем orderNumber по позиции в списке.
	 */
	@Transactional
	public List<AutomationTrigger> resort(List<AutomationTriggerDto> ordered) {
		for (int i = 0; i < ordered.size(); i++) {
			Long id = ordered.get(i).getId();
			if (id == null) continue;
			AutomationTrigger e = repository.findById(id).orElseThrow();
			e.setOrderNumber(i);
			repository.save(e);
		}
		return repository.findAll().stream().sorted().toList();
	}


	private void validate(AutomationTriggerDto dto) {
		if (dto.getName() == null || dto.getName().isBlank()) {
			throw new IllegalArgumentException("name required");
		}
		if (dto.getTriggerType() == null) {
			throw new IllegalArgumentException("eventType required");
		}
		if (dto.getExpression() == null || dto.getExpression().isBlank()) {
			throw new IllegalArgumentException("expression required");
		}
		if (dto.getAction() == null || dto.getAction().isBlank()) {
			throw new IllegalArgumentException("action required");
		}
	}


	private AutomationTriggerDto toDto(AutomationTrigger e) {
		return AutomationTriggerDto.builder()
				.id(e.getId())
				.name(e.getName())
				.description(e.getDescription())
				.triggerType(e.getTriggerType().name())
				.expression(e.getExpression())
				.action(e.getAction())
				.orderNumber(e.getOrderNumber())
				.build();
	}
}
