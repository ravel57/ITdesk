package ru.ravel.ItDesk.Controllers;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.ravel.ItDesk.Service.Impls.ClientServiceImpl;
import ru.ravel.ItDesk.Service.Interfaces.ClientServiceInterface;
import ru.ravel.ItDesk.Service.Interfaces.MessageServiceInterface;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

@Controller
public class TelegramBotController extends TelegramLongPollingBot {

    final String botName = "ITTaskboard_bot";
    final String token = getTokenFromFile();
    @Autowired
    ClientServiceInterface clientService;

    @Autowired
    MessageServiceInterface messageServiceInterface;

    public TelegramBotController() throws IOException {
        try {
//            this.clientService = clientService;
//            this.messageServiceInterface = messageServiceInterface;
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
        SendMessage message = new SendMessage();
        message.setChatId(update.getMessage().getChatId().toString());
        String clientID = update.getMessage().getFrom().getId().toString();
        String text = update.getMessage().getText();
        if (clientService.authorized(clientID) != null) {
            messageServiceInterface.saveMessage(clientID, text);
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
}

