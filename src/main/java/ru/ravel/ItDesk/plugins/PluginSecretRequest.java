package ru.ravel.ItDesk.plugins;

import lombok.Data;
import lombok.ToString;


@Data
public class PluginSecretRequest {

	@ToString.Exclude
	private String value;
}
