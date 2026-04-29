package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Knowledge;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.KnowledgeRepository;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

	private final KnowledgeRepository knowledgeRepository;
	private final EventPublisher eventPublisher;


	public List<Knowledge> getKnowledgeBase() {
		return knowledgeRepository.findAll().stream().sorted().toList();
	}


	public Knowledge newKnowledge(Knowledge knowledge) {
		Knowledge saved = knowledgeRepository.save(knowledge);

		eventPublisher.publish(TriggerType.KNOWLEDGE_BASE_ARTICLE_CREATED, Map.of(
				"knowledge", saved
		));

		return saved;
	}


	public Knowledge updateKnowledge(Knowledge knowledge) {
		Knowledge saved = knowledgeRepository.save(knowledge);

		eventPublisher.publish(TriggerType.KNOWLEDGE_BASE_ARTICLE_UPDATED, Map.of(
				"knowledge", saved
		));

		return saved;
	}


	public void deleteKnowledge(Long knowledgeId) {
		knowledgeRepository.deleteById(knowledgeId);
	}


	public List<Knowledge> resortKnowledge(@NotNull List<Knowledge> newKnowledgeOrder) {
		List<Knowledge> priorities = knowledgeRepository.findAll();
		for (Knowledge knowledge : priorities) {
			knowledge.setOrderNumber(newKnowledgeOrder.indexOf(knowledge));
		}
		knowledgeRepository.saveAll(priorities);
		return priorities.stream().sorted().toList();
	}

}
