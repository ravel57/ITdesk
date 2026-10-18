package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.LlmKbCountResponse;
import ru.ravel.ItDesk.dto.LlmKnowledgeDocument;
import ru.ravel.ItDesk.dto.LlmRequestBody;
import ru.ravel.ItDesk.dto.LlmResponse;
import ru.ravel.ItDesk.dto.LlmRetrainRequest;
import ru.ravel.ItDesk.dto.LlmRetrainResponse;
import ru.ravel.ItDesk.feign.LlmFeignClient;
import ru.ravel.ItDesk.model.Knowledge;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LlmService {

	private final LlmFeignClient llmFeignClient;
	private final KnowledgeService knowledgeService;

	@Value("${app.instance-name}")
	private String instanceName;


	public String askLlm(String question) {
		LlmRequestBody body = LlmRequestBody.builder()
				.query(question)
				.instanceName(instanceName)
				.build();
		LlmResponse response = llmFeignClient.askQuery(body);
		return response.getAnswer();
	}


	public LlmRetrainResponse retrainFromKnowledgeBase() {
		List<LlmKnowledgeDocument> documents = knowledgeService.getKnowledgeBase().stream()
				.map(this::toLlmDocument)
				.filter(document -> document.getText() != null && !document.getText().isBlank())
				.toList();

		LlmRetrainRequest request = LlmRetrainRequest.builder()
				.instanceName(instanceName)
				.documents(documents)
				.build();

		return llmFeignClient.retrain(request);
	}


	public LlmKbCountResponse getKbCount() {
		return llmFeignClient.kbCount(instanceName);
	}


	private LlmKnowledgeDocument toLlmDocument(Knowledge knowledge) {
		String title = knowledge.getTitle() == null ? "Без названия" : knowledge.getTitle().trim();
		String source = "БЗ #%d — %s".formatted(knowledge.getId(), title);

		String text = knowledge.getTexts() == null
				? ""
				: knowledge.getTexts().stream()
						.filter(Objects::nonNull)
						.map(String::trim)
						.filter(value -> !value.isBlank())
						.collect(Collectors.joining("\n\n"));

		String tags = knowledge.getTags() == null
				? ""
				: knowledge.getTags().stream()
						.filter(Objects::nonNull)
						.map(tag -> tag.getName() == null ? "" : tag.getName().trim())
						.filter(value -> !value.isBlank())
						.distinct()
						.collect(Collectors.joining(", "));

		return LlmKnowledgeDocument.builder()
				.source(source)
				.text(text)
				.tags(tags)
				.build();
	}

}