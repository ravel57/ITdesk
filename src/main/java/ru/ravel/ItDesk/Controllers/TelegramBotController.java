package ru.ravel.ItDesk.Controllers;


import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.*;
import java.util.Scanner;

public class TelegramBotController extends TelegramLongPollingBot {

    final String botName = "ITTaskboard_bot";
    final String token = getTokenFromFile();

    public TelegramBotController() throws IOException {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(this);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        this.getOptions().setMaxThreads(10);
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
        BufferedReader reader = new BufferedReader(new FileReader("token.txt"));
        String line = reader.readLine();
        reader.close();
        return line;
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
        SendMessage message = new SendMessage();
        message.setChatId(update.getMessage().getChatId().toString());
        switch (update.getMessage().getText()) {
            case "/start": {
                message.setText("Hello");
                break;
            }
            default: {
                message.setText(update.getMessage().getText());
                break;
            }
        }
        try {
            execute(message);
        } catch (Exception e) {
//        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}

