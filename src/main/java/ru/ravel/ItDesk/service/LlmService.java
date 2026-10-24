package ru.ravel.ItDesk.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.LlmGenerateRequest;
import ru.ravel.ItDesk.dto.LlmGenerateResponse;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LlmService {

	private static final Logger log = LoggerFactory.getLogger(LlmService.class);

	private final LlmFeignClient llmFeignClient;
	private final KnowledgeService knowledgeService;

	@Value("${app.instance-name}")
	private String instanceName;

	private final AtomicBoolean generateMissingLogged = new AtomicBoolean();


	public String askLlm(String question) {
		LlmRequestBody body = LlmRequestBody.builder()
				.query(question)
				.instanceName(instanceName)
				.build();
		LlmResponse response = llmFeignClient.askQuery(body);
		return response.getAnswer();
	}


	/**
	 * Свободная генерация текста по заданию (названия заявок, подсказки, черновики статей) — без поиска по базе
	 * знаний: POST /generate. /ask отвечает только по статьям и на такие задания возвращает «Код ошибки: 2».
	 * Пока сервис LLM не обновлён и /generate нет, используется прежний /ask.
	 */
	public String generate(String prompt) {
		try {
			LlmGenerateResponse response = llmFeignClient.generate(new LlmGenerateRequest(prompt, instanceName));
			return response == null ? null : response.text();
		} catch (FeignException.NotFound | FeignException.MethodNotAllowed e) {
			if (generateMissingLogged.compareAndSet(false, true)) {
				log.warn("LLM service has no POST /generate yet, falling back to /ask (knowledge base answers only)");
			}
			return askLlm(prompt);
		}
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