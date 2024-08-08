package ru.ravel.ItDesk.whatsappdto;

import lombok.AllArgsConstructor;
import lombok.Builder;

@Builder
@AllArgsConstructor
public class Media {

	public String mimetype;

	public String data;

	public int filesize;

	public String filename;
}