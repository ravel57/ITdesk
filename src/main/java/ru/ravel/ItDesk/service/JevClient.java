package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.service.JevSettingsService.JevConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;


/**
 * Тонкий HTTP-клиент TypeSafe Jev (POST /v1/systemone).
 * Jev не генерирует текст: на вход получает состояние и типизированные вопросы
 * (noul / choice / score), на выход отдаёт вероятности по каждому вопросу.
 * Параметры подключения берутся из конфига (app.jev.*), пороги — из настроек приложения. Если интеграция выключена или API недоступен,
 * {@link #evaluate} возвращает {@link Optional#empty()} и вызывающий код продолжает работу по обычным правилам.
 * Логирование — в логгер JEV (уровень JEV_LOG_LEVEL). Тексты обращений в лог не пишутся.
 */
@Service
public class JevClient {

	private static final Logger log = LoggerFactory.getLogger("JEV");

	private static final int MAX_STATE_CHARS = 8000;
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	private final ObjectMapper mapper = new ObjectMapper();
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private final JevSettingsService settingsService;
	private final OutboundUrlGuard outboundUrlGuard;


	public JevClient(JevSettingsService settingsService, OutboundUrlGuard outboundUrlGuard) {
		this.settingsService = settingsService;
		this.outboundUrlGuard = outboundUrlGuard;
	}


	public boolean isEnabled() {
		return settingsService.effective().isUsable();
	}


	/**
	 * Одна строка при старте: видно, что логгер JEV работает и с какой конфигурацией поднялся Jev.
	 * Сам Jev вызывается только из действий автоматизаций (jev.*), без правил запросов не будет.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void logConfiguration() {
		try {
			JevConfig config = settingsService.effective();
			boolean keySet = config.apiKey() != null && !config.apiKey().isBlank();
			if (config.isUsable()) {
				log.info("Jev is enabled: model={}, url={}, timeoutMs={}, thresholds={}, replyRequired>={}, "
								+ "replyNotRequired<={}, priorityExcluded={}. Calls come from automation actions jev.*",
						config.model(), config.url(), config.requestTimeoutMs(), config.thresholds(),
						config.replyRequiredThreshold(), config.replyNotRequiredThreshold(), config.priorityExcluded());
			} else {
				log.info("Jev is disabled: JEV_ENABLED={}, JEV_API_KEY {}", config.enabled(), keySet ? "set" : "not set");
			}
		} catch (Exception e) {
			log.warn("Unable to read Jev configuration: {}", e.getMessage());
		}
	}


	/**
	 * @param context   метка для логов, например {@code task=42}
	 * @param state     текст или структура, которую оценивает Jev
	 * @param questions вопросы: ключ = id вопроса, значение = {type, instructions, criteria}
	 * @return узел {@code answers} из ответа Jev либо empty при ошибке/выключенной интеграции
	 */
	public Optional<JsonNode> evaluate(String context, Object state, Map<String, Object> questions) {
		JevConfig config = settingsService.effective();
		if (!config.isUsable()) {
			log.debug("[{}] skipped: Jev disabled or API key is not set", context);
			return Optional.empty();
		}
		if (questions == null || questions.isEmpty()) {
			log.debug("[{}] skipped: no questions", context);
			return Optional.empty();
		}
		long startedAt = System.nanoTime();
		try {
			JsonNode answers = call(context, config, state, questions);
			if (log.isDebugEnabled()) {
				log.debug("[{}] answers: {}", context, answers);
			}
			return Optional.of(answers);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("[{}] request interrupted after {} ms", context, elapsedMs(startedAt));
			return Optional.empty();
		} catch (Exception e) {
			log.warn("[{}] request failed after {} ms, questions={}: {}",
					context, elapsedMs(startedAt), questions.keySet(), e.getMessage());
			return Optional.empty();
		}
	}


	private JsonNode call(String context, JevConfig config, Object state, Map<String, Object> questions) throws Exception {
		long startedAt = System.nanoTime();
		Object truncatedState = truncate(state);
		URI uri = outboundUrlGuard.validate(config.url());
		ObjectNode body = mapper.createObjectNode();
		body.set("state", mapper.valueToTree(truncatedState));
		body.put("model", config.model());
		body.set("questions", mapper.valueToTree(questions));
		HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofMillis(config.requestTimeoutMs()))
				.header("Authorization", "Bearer " + config.apiKey())
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
				.build();
		HttpResponse<String> response = send(context, request);
		if (response.statusCode() == 401) {
			throw new IllegalStateException("Jev отклонил API-ключ (HTTP 401)");
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Jev вернул HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
		}
		JsonNode root = mapper.readTree(response.body());
		JsonNode answers = root.get("answers");
		if (answers == null || !answers.isObject()) {
			throw new IllegalStateException("Jev вернул ответ без поля answers");
		}
		log.info("[{}] ok in {} ms: model={}, questions={}, stateChars={}{}, inputTokens={}, outputTokens={}",
				context,
				elapsedMs(startedAt),
				root.path("model").asText(config.model()),
				new ArrayList<>(questions.keySet()),
				stateLength(state),
				truncatedState != state ? " (truncated to " + MAX_STATE_CHARS + ")" : "",
				root.path("usage").path("input_tokens").asText("?"),
				root.path("usage").path("output_tokens").asText("?"));
		return answers;
	}


	// 429 / 529 — документация рекомендует повторить запрос с паузой
	private HttpResponse<String> send(String context, HttpRequest request) throws Exception {
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 429 || response.statusCode() == 529) {
			log.warn("[{}] HTTP {}, retrying in 500 ms", context, response.statusCode());
			Thread.sleep(500);
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		}
		return response;
	}


	private Object truncate(Object state) {
		if (state instanceof String text && text.length() > MAX_STATE_CHARS) {
			return text.substring(0, MAX_STATE_CHARS);
		}
		return state;
	}


	private int stateLength(Object state) {
		return state instanceof String text ? text.length() : -1;
	}


	private long elapsedMs(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000;
	}


	private String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 300 ? text.substring(0, 300) + "…" : text;
	}

}
