package ru.ravel.ItDesk.Models;

public class User {

    private long id;
    private String firstName;
    private String lastName;
    private String telegramId;


    public User() {
    }

    public User(long id,String firstName, String lastName, String telegramId) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.telegramId = telegramId;
    }

    public long getId() {
        return id;
    }
    public void setId(long id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getTelegramId() {
        return telegramId;
    }
    public void setTelegramId(String telegramId) {
        this.telegramId = telegramId;
    }

}
