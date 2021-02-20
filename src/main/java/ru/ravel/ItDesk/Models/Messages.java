package ru.ravel.ItDesk.Models;

import java.util.Date;

public class Messages {

    long id;
    long clientId;
    String text;
    Date date;

    public Messages(long id, long clientId, String text, Date date) {
        this.id = id;
        this.clientId = clientId;
        this.text = text;
        this.date = date;
    }

    public Messages() {
    }

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }

    public long getClientId() {
        return clientId;
    }
    public void setClientId(long clientId) {
        this.clientId = clientId;
    }

    public String getText() {
        return text;
    }
    public void setText(String text) {
        this.text = text;
    }

    public Date getDate() {
        return date;
    }
    public void setDate(Date date) {
        this.date = date;
    }

}
