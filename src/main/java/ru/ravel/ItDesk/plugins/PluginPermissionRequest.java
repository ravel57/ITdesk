package ru.ravel.ItDesk.plugins;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;


@Data
public class PluginPermissionRequest {

	private List<String> permissions = new ArrayList<>();
}
