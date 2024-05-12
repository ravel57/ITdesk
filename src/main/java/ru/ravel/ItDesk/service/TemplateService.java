package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
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
}
