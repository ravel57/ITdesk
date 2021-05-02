package ru.ravel.ItDesk.Controllers;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Service.Impls.ClientServiceImpl;
import ru.ravel.ItDesk.Service.Impls.MessageServiceImpl;

import java.util.ArrayList;
import java.util.List;

@Controller
public class TelegramBotController extends TelegramLongPollingBot {

    final String botName = "ITTaskboard_bot";
    final String token = System.getenv("itDeskBotToken");
    static List<Long> idsLockalChash = new ArrayList<>();

    @Autowired
    ClientServiceImpl clients;
    @Autowired
    MessageServiceImpl messages;

    public TelegramBotController() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            this.getOptions().setMaxThreads(10);
            botsApi.registerBot(this);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }


//    @Bean
//    public void botConnect() {
//        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
//        try {
//            telegramBotsApi.registerBot(this);
////                log.info("TelegramAPI started. Look for messages");
//        } catch (TelegramApiRequestException e) {
////                log.error("Cant Connect. Pause " + RECONNECT_PAUSE / 1000 + "sec and try again. Error: " + e.getMessage());
//            try {
////                Thread.sleep(RECONNECT_PAUSE);
//            } catch (Exception e1) {
//                e1.printStackTrace();
//                return;
//            }
//            botConnect();
//        }
//    }

//    @Bean
//    public void botConnect() {
//        try {
//            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
//            botsApi.registerBot(this);
//        } catch (TelegramApiException e) {
//            e.printStackTrace();
//        }
//    }

    @Override
    public String getBotUsername() {
        return botName;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        User user = update.getMessage().getFrom();
        Client client;
        long telegramId = user.getId();
        if (idsLockalChash.contains(telegramId)) {
            client = clients.getClientByTelegramId(telegramId);
        } else {
            if (clients.checkRegisteredByTelegramId(telegramId)) {
                client = clients.getClientByTelegramId(telegramId);
            } else {
                client = Client.builder()
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .userName(user.getUserName())
                        .telegramId(telegramId)
                        .build();
                clients.addClient(client);
            }
            idsLockalChash.add(telegramId);
            // todo delete asynchronously when idle time is exceeded
        }
        Message message = Message.builder()
                .text(update.getMessage().getText())
                .clientId(client.getId())
                .date(new java.util.Date((long) update.getMessage().getDate() * 1000))
                .messageType("message client")
                .build();
        messages.saveClientMessage(message);
        message.setId(messages.getClientsMessagesCount(client) - 1);
        // get messageId from DB
        messages.sendMessagesToFront(message);
    }


    public void sendMessage(Message message) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(clients.getTelegramIdByClientId(message.getClientId()));
        sendMessage.setText(message.getText());
//        switch (update.getMessage().getText()) {
//            case "/start": {
//                message.setText("Hello");
//                break;
//            }
//            default: {
//                message.setText(update.getMessage().getText());
//                break;
//            }
//        }
        try {
            execute(sendMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

