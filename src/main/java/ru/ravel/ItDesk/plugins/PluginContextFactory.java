package ru.ravel.ItDesk.plugins;

import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.service.UserService;

import java.util.HashMap;
import java.util.Map;


@Service
public class PluginContextFactory {

	private final ClientRepository clientRepository;
	private final UserService userService;


	public PluginContextFactory(ClientRepository clientRepository, UserService userService) {
		this.clientRepository = clientRepository;
		this.userService = userService;
	}


	public PluginExecutionContext createContext(NativeHookExecuteRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Plugin hook request is required");
		}
		if (request.getHook() == null || request.getHook().isBlank() || request.getHook().length() > 128) {
			throw new IllegalArgumentException("Invalid plugin hook name");
		}
		if (request.getEntityType() != null && request.getEntityType().length() > 32) {
			throw new IllegalArgumentException("Invalid plugin entity type");
		}

		var currentUser = userService.getCurrentUser();
		Map<String, Object> entity = new HashMap<>();
		Long organizationId = null;
		if ("CLIENT".equals(request.getEntityType()) && request.getEntityId() != null) {
			Client client = clientRepository.findById(request.getEntityId())
					.orElseThrow(() -> new IllegalArgumentException("Client not found: " + request.getEntityId()));
			// Do not let a plugin hook become an IDOR oracle: authorization is checked
			// before any PII is copied into the sandbox context.
			userService.assertUserCanAccessClient(currentUser, client);
			entity = clientToMap(client);
			organizationId = client.getOrganization() == null ? null : client.getOrganization().getId();
		}
		return PluginExecutionContext.builder()
				.currentUserId(currentUser.getId())
				.organizationId(organizationId)
				.entityType(request.getEntityType())
				.entityId(request.getEntityId())
				.entity(entity)
				.payload(request.getPayload() == null ? Map.of() : request.getPayload())
				.build();
	}


	private Map<String, Object> clientToMap(Client client) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", client.getId());
		map.put("firstname", client.getFirstname());
		map.put("lastname", client.getLastname());
		map.put("moreInfo", client.getMoreInfo());
		map.put("telegramId", client.getTelegramId());
		map.put("whatsappRecipient", client.getWhatsappRecipient());
		map.put("phoneNumber", client.getPhoneNumber());
		map.put("email", client.getEmail());
		map.put("sourceChannel", client.getSourceChannel());
		map.put("messageFrom", client.getMessageFrom() != null ? client.getMessageFrom().name() : null);
		if (client.getOrganization() != null) {
			Map<String, Object> organization = new HashMap<>();
			organization.put("id", client.getOrganization().getId());
			organization.put("name", client.getOrganization().getName());
			map.put("organization", organization);
		} else {
			map.put("organization", null);
		}
		return map;
	}

}