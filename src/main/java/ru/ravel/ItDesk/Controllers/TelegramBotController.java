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
    final String token = getTokenFromFile();
    static List<String> idsLockalChash = new ArrayList<>();

    @Autowired
    ClientServiceInterface clientService;

    @Autowired
    MessageServiceInterface messageServiceInterface;

    public TelegramBotController() throws IOException {
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
        String clientID = update.getMessage().getFrom().getId().toString();
        String text = update.getMessage().getText();
        if (!idsLockalChash.contains(clientID)) {
            if (!clientService.registered(clientID))
                clientService.addUser(Client.builder()
                        .firstName(update.getMessage().getFrom().getFirstName())
                        .lastName(update.getMessage().getFrom().getLastName())
                        .userName(update.getMessage().getFrom().getUserName())
                        .telegramId(clientID)
                        .build());
            idsLockalChash.add(clientID);
        }
        messageServiceInterface.saveMessage(clientID, text);
    }


    public void sendMessage(String telegramID) {
        SendMessage message = new SendMessage();
        message.setChatId(telegramID);
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
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

