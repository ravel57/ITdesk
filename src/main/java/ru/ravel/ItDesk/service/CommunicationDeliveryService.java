package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.MessageFrom;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consumes COMMUNICATION_SEND_REQUESTED outbox events and delegates delivery
 * to a channel adapter. Throwing an exception is intentional: AutomationItemProcessor
 * will keep the event in NEW state and retry it with backoff.
 */
@Slf4j
@Service
public class CommunicationDeliveryService {

    private final ClientRepository clientRepository;
    private final MessageRepository messageRepository;
    private final Map<MessageFrom, CommunicationChannelSender> senders;

    public CommunicationDeliveryService(
            ClientRepository clientRepository,
            MessageRepository messageRepository,
            List<CommunicationChannelSender> channelSenders
    ) {
        this.clientRepository = clientRepository;
        this.messageRepository = messageRepository;
        EnumMap<MessageFrom, CommunicationChannelSender> senderMap = new EnumMap<>(MessageFrom.class);
        for (CommunicationChannelSender sender : channelSenders) {
            CommunicationChannelSender previous = senderMap.put(sender.channel(), sender);
            if (previous != null) {
                throw new IllegalStateException("More than one communication sender for channel " + sender.channel());
            }
        }
        this.senders = Map.copyOf(senderMap);
    }

    public void deliver(Event event) throws Exception {
        JsonNode payload = Objects.requireNonNull(event.getPayload(), "communication event payload is null");
        Long clientId = requiredLong(payload, "clientId");
        Long messageId = requiredLong(payload, "messageId");
        MessageFrom channel = requiredChannel(payload, "channel");

        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalStateException("Client not found: " + clientId));
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalStateException("Message not found: " + messageId));

        if (Boolean.TRUE.equals(message.getDeleted())) {
            log.info("Communication delivery skipped for deleted message, eventId={}, messageId={}", event.getId(), messageId);
            return;
        }

        if (!messageRepository.existsByIdAndClientId(messageId, clientId)) {
            throw new IllegalStateException("Message " + messageId + " does not belong to client " + clientId);
        }

        // Portal delivery is the persisted history plus WebSocket updates, with no external adapter.
        if (channel == MessageFrom.PORTAL) {
            return;
        }

        CommunicationChannelSender sender = senders.get(channel);
        if (sender == null) {
            throw new IllegalStateException("No communication sender registered for channel " + channel);
        }

        sender.send(client, message);
        // Telegram and media senders can enrich the entity (external id, dimensions, etc.).
        messageRepository.save(message);
        log.info("Communication delivered, eventId={}, clientId={}, messageId={}, channel={}",
                event.getId(), clientId, messageId, channel);
    }

    private Long requiredLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing communication event field: " + field);
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        throw new IllegalArgumentException("Communication event field '" + field + "' must be a long");
    }

    private MessageFrom requiredChannel(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing communication event field: " + field);
        }
        try {
            return MessageFrom.valueOf(value.asText().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported communication channel: " + value.asText(), ex);
        }
    }
}
