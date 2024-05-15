package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Template;
import ru.ravel.ItDesk.repository.TemplateRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TemplateService {

	private final TemplateRepository templateRepository;


	public List<Template> getTemplates() {
		return templateRepository.findAll();
	}


	public Template newTemplate(Template template) {
		return templateRepository.save(template);
	}


	public Template updateTemplate(Template template) {
		return templateRepository.save(template);
	}


	public void deleteTemplate(Long templateId) {
		templateRepository.deleteById(templateId);
	}


	public List<Template> resortTemplates(@NotNull List<Template> templates) {
		for (int i = 0; i < templates.size(); i++) {
			templates.get(i).setOrderNumber(i);
		}
		List<Template> list = templates.stream().sorted().toList();
		templateRepository.saveAll(list);
		return list;
	}
}
