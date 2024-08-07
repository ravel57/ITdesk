package ru.ravel.ItDesk.whatsapp;

import lombok.Builder;

import java.util.List;

@Builder
public class UpdateResponse {

	public String result;

	public Integer pages;

	public Integer elements;

	public Integer page;

	public List<ResponseData> data;

}
