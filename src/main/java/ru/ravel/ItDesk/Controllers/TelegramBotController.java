package ru.ravel.ItDesk.Controllers;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.ravel.ItDesk.Models.Client;
import ru.ravel.ItDesk.Models.Message;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Controller
public class TelegramBotController extends TelegramLongPollingBot {

    final String botName = "ITTaskboard_bot";
    final String token = System.getenv("itDeskBotToken");
    static List<Long> idsLockalChash = new ArrayList<>();

    @Autowired
    ClientServiceInterface clients;
    @Autowired
    MessageServiceInterface messages;

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

    private String getTokenFromFile() throws IOException {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("token.txt"));
            String line = reader.readLine();
            reader.close();
            return line;
        } catch (IOException e) {
            throw e;
        }
    }

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
        long telegramId = update.getMessage().getFrom().getId();
        if (!idsLockalChash.contains(telegramId)) {
            if (!clients.checkRegisteredByTelegramId(telegramId))
                clients.addUser(Client.builder()
                        .firstName(update.getMessage().getFrom().getFirstName())
                        .lastName(update.getMessage().getFrom().getLastName())
                        .userName(update.getMessage().getFrom().getUserName())
                        .telegramId(telegramId)
                        .build());
            idsLockalChash.add(clients.getClientByTelegramId(telegramId).getTelegramId());
        }
        Message message = Message.builder()
                .text(update.getMessage().getText())
                .clientId(clients.getClientByTelegramId(telegramId).getId())
                .date(new java.util.Date((long) update.getMessage().getDate() * 1000))
                .build();
        messages.saveMessage(message);
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

