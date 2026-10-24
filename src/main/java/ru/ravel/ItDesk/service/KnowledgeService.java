package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Knowledge;
import ru.ravel.ItDesk.model.KnowledgeStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.KnowledgeRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

	private final KnowledgeRepository knowledgeRepository;
	private final EventPublisher eventPublisher;


	/**
	 * Опубликованные статьи: их видят операторы в чате, подбирает Jev, получает LLM и индексирует поиск.
	 */
	public List<Knowledge> getKnowledgeBase() {
		return knowledgeRepository.findAllByStatus(KnowledgeStatus.PUBLISHED).stream().sorted().toList();
	}


	/**
	 * Все статьи вместе с черновиками — для редакторов базы знаний.
	 */
	public List<Knowledge> getKnowledgeBaseWithDrafts() {
		return knowledgeRepository.findAll().stream().sorted().toList();
	}


	public Knowledge newKnowledge(Knowledge knowledge) {
		if (knowledge.getStatus() == null) {
			knowledge.setStatus(KnowledgeStatus.PUBLISHED);
		}
		if (knowledge.getOrderNumber() == null) {
			knowledge.setOrderNumber(knowledgeRepository.maxOrderNumber() + 1);
		}
		Knowledge saved = knowledgeRepository.save(knowledge);

		if (!saved.isDraft()) {
			eventPublisher.publish(TriggerType.KNOWLEDGE_BASE_ARTICLE_CREATED, Map.of(
					"knowledge", saved
			));
		}

		return saved;
	}


	public Knowledge updateKnowledge(Knowledge knowledge) {
		// Состояние и происхождение статьи редактор не меняет: публикация — отдельное действие
		knowledgeRepository.findById(knowledge.getId()).ifPresent(existing -> {
			knowledge.setStatus(existing.getStatus());
			knowledge.setSourceTaskId(existing.getSourceTaskId());
			if (knowledge.getOrderNumber() == null) {
				knowledge.setOrderNumber(existing.getOrderNumber());
			}
		});
		Knowledge saved = knowledgeRepository.save(knowledge);

		if (!saved.isDraft()) {
			eventPublisher.publish(TriggerType.KNOWLEDGE_BASE_ARTICLE_UPDATED, Map.of(
					"knowledge", saved
			));
		}

		return saved;
	}


	/**
	 * Черновик статьи, сформированный из заявки. Встаёт в конец списка и ждёт проверки редактором.
	 */
	public Knowledge createDraft(String title, List<String> texts, Long sourceTaskId) {
		return knowledgeRepository.save(Knowledge.builder()
				.title(title)
				.texts(new ArrayList<>(texts))
				.tags(new ArrayList<>())
				.status(KnowledgeStatus.DRAFT)
				.sourceTaskId(sourceTaskId)
				.orderNumber(knowledgeRepository.maxOrderNumber() + 1)
				.build());
	}


	public boolean hasArticleFromTask(Long taskId) {
		return taskId != null && knowledgeRepository.existsBySourceTaskId(taskId);
	}


	@Transactional
	public Knowledge publish(Long knowledgeId) {
		Knowledge knowledge = knowledgeRepository.findById(knowledgeId)
				.orElseThrow(() -> new NoSuchElementException("Статья #" + knowledgeId + " не найдена"));
		if (!knowledge.isDraft()) {
			return knowledge;
		}
		knowledge.setStatus(KnowledgeStatus.PUBLISHED);
		Knowledge saved = knowledgeRepository.save(knowledge);

		eventPublisher.publish(TriggerType.KNOWLEDGE_BASE_ARTICLE_PUBLISHED, Map.of(
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
