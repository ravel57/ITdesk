package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.ravel.ItDesk.model.OlaStatus;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OlaInfoDto {
    private OlaStatus status;
    private ZonedDateTime startedAt;
    private ZonedDateTime deadline;
    private ZonedDateTime warningAt;
    private Long durationSeconds;
    private Long secondsLeft;
    private Double percent;
    private Boolean paused;
    private Boolean warning;
    private Boolean breached;
}
