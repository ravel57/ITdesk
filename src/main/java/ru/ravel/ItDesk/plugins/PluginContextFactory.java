package ru.ravel.ItDesk.plugins;

import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.repository.ClientRepository;

import java.util.HashMap;
import java.util.Map;


@Service
public class PluginContextFactory {

	private final ClientRepository clientRepository;


	public PluginContextFactory(ClientRepository clientRepository) {
		this.clientRepository = clientRepository;
	}


	public PluginExecutionContext createContext(NativeHookExecuteRequest request) {
		Map<String, Object> entity = new HashMap<>();
		if ("CLIENT".equals(request.getEntityType()) && request.getEntityId() != null) {
			Client client = clientRepository.findById(request.getEntityId())
					.orElseThrow(() -> new IllegalArgumentException("Client not found: " + request.getEntityId()));
			entity = clientToMap(client);
		}
		return PluginExecutionContext.builder()
				.entityType(request.getEntityType())
				.entityId(request.getEntityId())
				.entity(entity)
				.payload(request.getPayload())
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
		return map;
	}

}