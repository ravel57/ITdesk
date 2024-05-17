package ru.ravel.ItDesk.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Getter
@Setter
@EqualsAndHashCode
public class ExecuteFuture {
	ExecutorService executor = Executors.newSingleThreadExecutor();
	Future<?> future;
}
