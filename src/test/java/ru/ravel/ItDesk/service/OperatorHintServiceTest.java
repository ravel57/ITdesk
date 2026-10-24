package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import ru.ravel.ItDesk.dto.OperatorHintStatsDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.OperatorHintRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


class OperatorHintServiceTest {

	private static final String PUBLISHING_GRAPH = "{\"nodes\":[{\"id\":\"n\",\"type\":\"OPERATION\",\"config\":{\"operation\":\"hint.publish\"}}]}";

	private final OperatorHintRepository repository = mock(OperatorHintRepository.class);
	private final ClientRepository clients = mock(ClientRepository.class);
	private final AutomationTriggerRepository triggers = mock(AutomationTriggerRepository.class);
	private final ClientService clientService = mock(ClientService.class);
	private final UserService userService = mock(UserService.class);
	private final OperatorHintService service = new OperatorHintService(repository, clients, triggers, clientService, userService, new ObjectMapper());
	private final Client client = Client.builder().id(7L).build();
	private final User operator = new User();
	private final AutomationTrigger rule = AutomationTrigger.builder().id(34L).name("Мои подсказки").workflowDefinition(PUBLISHING_GRAPH)
			.automationRuleStatus(AutomationRuleStatus.ENABLED).build();


	@BeforeEach
	void setUp() {
		operator.setId(3L);
		when(clients.findById(7L)).thenReturn(Optional.of(client));
		when(userService.getCurrentUser()).thenReturn(operator);
		when(repository.save(any())).thenAnswer(call -> {
			OperatorHint hint = call.getArgument(0);
			if (hint.getId() == null) hint.setId(70L);
			return hint;
		});
	}


	@Test
	void publishMarksNoteWithHintAndRemembersSourceAndRule() {
		when(clientService.sendMessageForSystem(eq(7L), any())).thenAnswer(call -> {
			Message note = call.getArgument(1);
			note.setId(300L);
			return note;
		});

		OperatorHint hint = service.publish(7L, 200L, "Task", "Закрытые заявки", 92L, "Не работает ЭЦП", 0.9, rule, "Jev: подсказка");

		ArgumentCaptor<Message> note = ArgumentCaptor.forClass(Message.class);
		verify(clientService).sendMessageForSystem(eq(7L), note.capture());
		assertThat(note.getValue().getIsComment()).isTrue();
		assertThat(note.getValue().getOperatorHintId()).isEqualTo(70L);
		assertThat(hint.getNoteMessageId()).isEqualTo(300L);
		assertThat(hint.getSource()).isEqualTo("task");
		assertThat(hint.getSourceLabel()).isEqualTo("Закрытые заявки");
		assertThat(hint.getRuleId()).isEqualTo(34L);
		assertThat(hint.getRuleName()).isEqualTo("Мои подсказки");
	}


	@Test
	void customSourceWithoutLabelIsShownByKeyAndInvalidKeyIsRejected() {
		when(clientService.sendMessageForSystem(eq(7L), any())).thenAnswer(call -> call.getArgument(1));

		assertThat(service.publish(7L, null, "faq", "", null, null, null, rule, "текст").getSourceLabel()).isEqualTo("faq");
		assertThat(service.publish(7L, null, "article", null, null, null, null, rule, "текст").getSourceLabel()).isEqualTo("Статьи базы знаний");
		assertThatThrownBy(() -> service.publish(7L, null, "Статьи", null, null, null, null, rule, "текст"))
				.isInstanceOf(IllegalArgumentException.class);
	}


	@Test
	void ratingRecordsWhoAndCanBeWithdrawn() {
		OperatorHint hint = OperatorHint.builder().id(70L).clientId(7L).source("article").build();
		when(repository.findById(70L)).thenReturn(Optional.of(hint));

		assertThat(service.rate(70L, false).helpful()).isFalse();
		assertThat(hint.getFeedbackUserId()).isEqualTo(3L);
		assertThat(hint.getFeedbackAt()).isNotNull();

		assertThat(service.rate(70L, null).helpful()).isNull();
		assertThat(hint.getFeedbackUserId()).isNull();
		assertThat(hint.getFeedbackAt()).isNull();
		verify(userService, times(2)).assertCurrentUserCanAccessClient(client);
	}


	@Test
	void foreignClientHintCannotBeRated() {
		OperatorHint hint = OperatorHint.builder().id(70L).clientId(7L).source("article").build();
		when(repository.findById(70L)).thenReturn(Optional.of(hint));
		doThrow(new AccessDeniedException("нет доступа")).when(userService).assertCurrentUserCanAccessClient(client);

		assertThatThrownBy(() -> service.rate(70L, true)).isInstanceOf(AccessDeniedException.class);
		verify(repository, never()).save(any());
	}


	@Test
	void observerSeesNoHints() {
		when(userService.isObserver(operator)).thenReturn(true);
		assertThat(service.hintsOfClient(7L)).isEmpty();
		verify(repository, never()).findAllByClientIdOrderByIdAsc(anyLong());
	}


	@Test
	void statsUseCurrentRuleNameAndKeepDeletedRules() {
		AutomationTrigger renamed = AutomationTrigger.builder().id(34L).name("Новое имя").workflowDefinition(PUBLISHING_GRAPH)
				.automationRuleStatus(AutomationRuleStatus.DISABLED).build();
		AutomationTrigger other = AutomationTrigger.builder().id(40L).name("Без подсказок").workflowDefinition("{\"nodes\":[]}")
				.automationRuleStatus(AutomationRuleStatus.ENABLED).build();
		when(triggers.findAll()).thenReturn(List.of(renamed, other));
		when(repository.statsSince(any(), isNull())).thenReturn(List.of(
				new Object[]{34L, "Старое имя", "task", "Закрытые заявки", 4L, 1L, 2L, 0.8},
				new Object[]{12L, "Удалённое правило", "faq", "FAQ", 2L, 0L, 0L, null}));

		OperatorHintStatsDto stats = service.stats(30, null);

		assertThat(stats.rules()).extracting(OperatorHintStatsDto.Rule::name).containsExactly("Новое имя", "Удалённое правило");
		assertThat(stats.rules().get(0).exists()).isTrue();
		assertThat(stats.rules().get(0).enabled()).isFalse();
		assertThat(stats.rules().get(0).sources()).containsExactly(new OperatorHintStatsDto.Source("task", "Закрытые заявки", 4, 1, 2, 0.8));
		assertThat(stats.rules().get(1).exists()).isFalse();
		assertThat(stats.publishingRules()).extracting(OperatorHintStatsDto.RuleRef::id).containsExactly(34L);
	}

}
