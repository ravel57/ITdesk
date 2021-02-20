package ru.ravel.ItDesk.Models;

public class Client {

    private long id;
    private String firstName;
    private String lastName;
    private long organizationId;
    private String telegramId;
    private long whatsappId;
    private String cabinetNumber;
    private String phoneNumber;
    private String email;


    public Client() {
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

    public long getOrganizationId() {
        return organizationId;
    }
    public void setOrganizationId(long organizationId) {
        this.organizationId = organizationId;
    }

    public String getTelegramId() {
        return telegramId;
    }
    public void setTelegramId(String telegramId) {
        this.telegramId = telegramId;
    }

    public long getWhatsappId() {
        return whatsappId;
    }
    public void setWhatsappId(long whatsappId) {
        this.whatsappId = whatsappId;
    }

    public String getCabinetNumber() {
        return cabinetNumber;
    }
    public void setCabinetNumber(String cabinetNumber) {
        this.cabinetNumber = cabinetNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }
    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getEmail() {
        return email;
    }
    public void setEmail(String email) {
        this.email = email;
    }

}
