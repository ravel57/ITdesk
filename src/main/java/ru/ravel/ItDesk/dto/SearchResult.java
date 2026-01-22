package ru.ravel.ItDesk.dto;


import lombok.Data;

@Data
public class SearchResult {
	String id;
	String text;
	String tags;
	String source;
	Integer chunk_index;
	Float distance;
	Float score;
}
