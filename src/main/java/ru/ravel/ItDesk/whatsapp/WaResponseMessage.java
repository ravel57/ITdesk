package ru.ravel.ItDesk.whatsapp;

import com.fasterxml.jackson.annotation.JsonProperty;

public class WaResponseMessage {

	public String _id;

	public String id;

	public int ack;

	public boolean hasMedia;

	public String mediaKey;

	public String body;

	public String type;

	public int timestamp;

	public String from;

	public String from_name;

	public String to;

	public boolean isForwarded;

}
