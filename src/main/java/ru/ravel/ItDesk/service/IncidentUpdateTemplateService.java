package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.incident.IncidentUpdateTemplate;
import ru.ravel.ItDesk.repository.IncidentUpdateTemplateRepository;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Справочник шаблонов обновлений инцидента (настройки → «Шаблоны обновлений инцидентов»).
 */
@Service
@RequiredArgsConstructor
public class IncidentUpdateTemplateService {

	private static final int TITLE_MAX_LENGTH = 255;

	private final IncidentUpdateTemplateRepository templateRepository;


	@Transactional(readOnly = true)
	public List<IncidentUpdateTemplate> getAll() {
		return templateRepository.findAllByOrderByOrderNumberAscIdAsc();
	}

	@Transactional
	public IncidentUpdateTemplate create(IncidentUpdateTemplate request) {
		validate(request, null);
		IncidentUpdateTemplate template = IncidentUpdateTemplate.builder()
				.key(generateKey())
				.title(request.getTitle().trim())
				.text(request.getText().trim())
				.orderNumber((int) templateRepository.count())
				.build();
		return templateRepository.save(template);
	}

	@Transactional
	public IncidentUpdateTemplate update(Long id, IncidentUpdateTemplate request) {
		IncidentUpdateTemplate template = templateRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));
		validate(request, id);
		template.setTitle(request.getTitle().trim());
		template.setText(request.getText().trim());
		return templateRepository.save(template);
	}

	@Transactional
	public void delete(Long id) {
		IncidentUpdateTemplate template = templateRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Шаблон не найден"));
		templateRepository.delete(template);
	}

	@Transactional
	public List<IncidentUpdateTemplate> resort(List<Long> orderedIds) {
		if (orderedIds != null) {
			for (int i = 0; i < orderedIds.size(); i++) {
				Long id = orderedIds.get(i);
				if (id == null) continue;
				int orderNumber = i;
				templateRepository.findById(id).ifPresent(template -> template.setOrderNumber(orderNumber));
			}
		}
		return getAll();
	}

	private void validate(IncidentUpdateTemplate request, Long id) {
		if (request == null || request.getTitle() == null || request.getTitle().isBlank()) {
			throw new IllegalArgumentException("Название шаблона обязательно");
		}
		if (request.getTitle().trim().length() > TITLE_MAX_LENGTH) {
			throw new IllegalArgumentException("Название шаблона не должно превышать %d символов".formatted(TITLE_MAX_LENGTH));
		}
		if (request.getText() == null || request.getText().isBlank()) {
			throw new IllegalArgumentException("Текст шаблона обязателен");
		}
		if (templateRepository.existsByTitleIgnoreCaseAndIdNot(request.getTitle().trim(), id == null ? -1L : id)) {
			throw new IllegalArgumentException("Шаблон с таким названием уже существует");
		}
	}

	/**
	 * Ключ не редактируется: по нему записи таймлайна ссылаются на шаблон.
	 */
	private String generateKey() {
		String key;
		do {
			byte[] bytes = new byte[6];
			ThreadLocalRandom.current().nextBytes(bytes);
			key = "custom-" + HexFormat.of().formatHex(bytes);
		} while (templateRepository.existsByKey(key));
		return key;
	}
}
