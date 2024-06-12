package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Knowledge;
import ru.ravel.ItDesk.repository.KnowledgeRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

	private final KnowledgeRepository knowledgeRepository;


	public List<Knowledge> getKnowledgeBase() {
		return knowledgeRepository.findAll().stream().sorted().toList();
	}


	public Knowledge newKnowledge(Knowledge knowledge) {
		return knowledgeRepository.save(knowledge);
	}


	public Knowledge updateKnowledge(Knowledge knowledge) {
		return knowledgeRepository.save(knowledge);
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
