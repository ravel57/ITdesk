package ru.ravel.ItDesk.whatsapp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UpdateBody {

	@JsonProperty("WhatsappID")
	public String whatsappID;

	public String date;

	public Action action;

	@JsonProperty("page_cnt")
	public Integer pageCnt;

	public Integer page;

}
