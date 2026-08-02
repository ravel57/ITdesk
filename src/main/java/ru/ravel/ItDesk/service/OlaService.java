package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.OlaInfoDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.TaskSupportLineStageRepository;

import java.time.ZonedDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OlaService {

    private final OlaWorkingTimeService workingTimeService;
    private final TaskSupportLineStageRepository stageRepository;

    public void initializeFields(Task task, SupportLine line, ZonedDateTime now) {
        ZonedDateTime safeNow = Objects.requireNonNullElse(now, ZonedDateTime.now());
        task.setEnteredCurrentLineAt(safeNow);
        task.setOlaPausedAt(null);
        task.setOlaRemainingSecondsOnPause(null);

        long durationSeconds = workingTimeService.durationSeconds(line);
        if (line == null || !Boolean.TRUE.equals(line.getOlaEnabled()) || durationSeconds <= 0) {
            task.setOlaStatus(OlaStatus.DISABLED);
            task.setOlaDurationSeconds(null);
            task.setOlaUseWorkingTime(false);
            task.setOlaDeadline(null);
            task.setOlaWarningAt(null);
            task.setOlaInfo(buildInfo(task, safeNow));
            return;
        }

        boolean useWorkingTime = Boolean.TRUE.equals(line.getOlaUseWorkingTime())
                || line.getOlaUnit() == OlaUnit.WORKING_DAYS;
        int warningPercent = Math.max(1, Math.min(100, Objects.requireNonNullElse(line.getOlaWarningPercent(), 80)));
        long warningSeconds = Math.max(1L, Math.round(durationSeconds * (warningPercent / 100.0d)));

        task.setOlaDurationSeconds(durationSeconds);
        task.setOlaUseWorkingTime(useWorkingTime);
        task.setOlaDeadline(workingTimeService.addSeconds(safeNow, durationSeconds, useWorkingTime));
        task.setOlaWarningAt(workingTimeService.addSeconds(safeNow, warningSeconds, useWorkingTime));
        task.setOlaStatus(OlaStatus.OK);
        task.setOlaInfo(buildInfo(task, safeNow));
    }

    @Transactional
    public void createStage(Task task, User actor, String reason) {
        if (task == null || task.getId() == null || task.getSupportLine() == null) {
            return;
        }
        stageRepository.findFirstByTaskIdAndLeftAtIsNullOrderByEnteredAtDesc(task.getId())
                .ifPresent(existing -> closeStage(existing, Objects.requireNonNullElse(task.getEnteredCurrentLineAt(), ZonedDateTime.now())));
        stageRepository.flush();
        stageRepository.save(TaskSupportLineStage.builder()
                .task(task)
                .supportLine(task.getSupportLine())
                .enteredAt(Objects.requireNonNullElse(task.getEnteredCurrentLineAt(), ZonedDateTime.now()))
                .olaDeadline(task.getOlaDeadline())
                .olaWarningAt(task.getOlaWarningAt())
                .status(Objects.requireNonNullElse(task.getOlaStatus(), OlaStatus.DISABLED))
                .durationSeconds(task.getOlaDurationSeconds())
                .useWorkingTime(Boolean.TRUE.equals(task.getOlaUseWorkingTime()))
                .transferredBy(actor)
                .transferReason(normalizeReason(reason))
                .build());
    }

    @Transactional
    public void changeLine(Task task, SupportLine newLine, User actor, String reason) {
        ZonedDateTime now = ZonedDateTime.now();
        if (task != null && task.getOlaPausedAt() != null) {
            finishPauseAt(task, now);
        }
        closeCurrentStage(task, now);
        task.setSupportLine(newLine);
        initializeFields(task, newLine, now);
        createStage(task, actor, reason);
    }

    @Transactional
    public void complete(Task task) {
        if (task == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        if (task.getOlaPausedAt() != null) {
            finishPauseAt(task, now);
        }
        closeCurrentStage(task, now);
        if (task.getOlaDeadline() == null || task.getSupportLine() == null
                || !Boolean.TRUE.equals(task.getSupportLine().getOlaEnabled())) {
            task.setOlaStatus(OlaStatus.DISABLED);
        } else {
            task.setOlaStatus(resolveBreached(task, now) ? OlaStatus.BREACHED : OlaStatus.COMPLETED);
        }
        task.setOlaPausedAt(null);
        task.setOlaRemainingSecondsOnPause(null);
        task.setOlaInfo(buildInfo(task, now));
    }

    @Transactional
    public void restart(Task task, User actor, String reason) {
        if (task == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        if (task.getOlaPausedAt() != null) {
            finishPauseAt(task, now);
        }
        closeCurrentStage(task, now);
        initializeFields(task, task.getSupportLine(), now);
        createStage(task, actor, reason);
        synchronizeWithTaskState(task);
    }

    @Transactional
    public void pause(Task task) {
        if (task == null || task.getOlaDeadline() == null || task.getOlaPausedAt() != null
                || task.getOlaStatus() == OlaStatus.COMPLETED || task.getOlaStatus() == OlaStatus.DISABLED) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        if (resolveBreached(task, now)) {
            task.setOlaStatus(OlaStatus.BREACHED);
            updateActiveStage(task, stage -> {
                stage.setStatus(OlaStatus.BREACHED);
                if (stage.getBreachedAt() == null) {
                    stage.setBreachedAt(task.getOlaDeadline());
                }
            });
            task.setOlaInfo(buildInfo(task, now));
            return;
        }
        long remaining = workingTimeService.secondsBetween(now, task.getOlaDeadline(), Boolean.TRUE.equals(task.getOlaUseWorkingTime()));
        task.setOlaRemainingSecondsOnPause(Math.max(0L, remaining));
        task.setOlaPausedAt(now);
        task.setOlaStatus(OlaStatus.PAUSED);
        updateActiveStage(task, stage -> stage.setStatus(OlaStatus.PAUSED));
        task.setOlaInfo(buildInfo(task, now));
    }

    @Transactional
    public void resume(Task task) {
        if (task == null || task.getOlaPausedAt() == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        long remaining = Math.max(0L, Objects.requireNonNullElse(task.getOlaRemainingSecondsOnPause(), 0L));
        boolean useWorkingTime = Boolean.TRUE.equals(task.getOlaUseWorkingTime());
        task.setOlaDeadline(workingTimeService.addSeconds(now, remaining, useWorkingTime));

        long total = Math.max(1L, Objects.requireNonNullElse(task.getOlaDurationSeconds(), remaining));
        int warningPercent = task.getSupportLine() == null
                ? 80
                : Math.max(1, Math.min(100, Objects.requireNonNullElse(task.getSupportLine().getOlaWarningPercent(), 80)));
        long remainingAtWarning = Math.round(total * ((100 - warningPercent) / 100.0d));
        long untilWarning = Math.max(0L, remaining - remainingAtWarning);
        task.setOlaWarningAt(workingTimeService.addSeconds(now, untilWarning, useWorkingTime));

        long pausedSeconds = Math.max(0L, workingTimeService.secondsBetween(
                task.getOlaPausedAt(),
                now,
                useWorkingTime
        ));
        task.setOlaPausedAt(null);
        task.setOlaRemainingSecondsOnPause(null);
        task.setOlaStatus(resolveStatus(task, now));
        updateActiveStage(task, stage -> {
            stage.setOlaDeadline(task.getOlaDeadline());
            stage.setOlaWarningAt(task.getOlaWarningAt());
            stage.setPausedSeconds(Objects.requireNonNullElse(stage.getPausedSeconds(), 0L) + pausedSeconds);
            stage.setStatus(task.getOlaStatus());
        });
        task.setOlaInfo(buildInfo(task, now));
    }

    public Task enrich(Task task) {
        if (task != null) {
            task.setOlaInfo(buildInfo(task, ZonedDateTime.now()));
        }
        return task;
    }

    public OlaInfoDto buildInfo(Task task, ZonedDateTime now) {
        if (task == null) {
            return null;
        }
        ZonedDateTime safeNow = Objects.requireNonNullElse(now, ZonedDateTime.now());
        OlaStatus status = resolveStatus(task, safeNow);
        long secondsLeft;
        if (task.getOlaPausedAt() != null) {
            secondsLeft = Objects.requireNonNullElse(task.getOlaRemainingSecondsOnPause(), 0L);
        } else if (task.getOlaDeadline() != null) {
            secondsLeft = workingTimeService.secondsBetween(
                    safeNow,
                    task.getOlaDeadline(),
                    Boolean.TRUE.equals(task.getOlaUseWorkingTime())
            );
        } else {
            secondsLeft = 0L;
        }
        Long duration = task.getOlaDurationSeconds();
        Double percent = duration == null || duration <= 0
                ? null
                : Math.max(0.0d, Math.min(100.0d, (secondsLeft * 100.0d) / duration));
        return OlaInfoDto.builder()
                .status(status)
                .startedAt(task.getEnteredCurrentLineAt())
                .deadline(task.getOlaDeadline())
                .warningAt(task.getOlaWarningAt())
                .durationSeconds(duration)
                .secondsLeft(secondsLeft)
                .percent(percent)
                .paused(status == OlaStatus.PAUSED)
                .warning(status == OlaStatus.WARNING)
                .breached(status == OlaStatus.BREACHED)
                .build();
    }

    public OlaStatus resolveStatus(Task task, ZonedDateTime now) {
        if (task == null || task.getSupportLine() == null || !Boolean.TRUE.equals(task.getSupportLine().getOlaEnabled())
                || task.getOlaDeadline() == null) {
            return OlaStatus.DISABLED;
        }
        if (task.getOlaPausedAt() != null || task.getOlaStatus() == OlaStatus.PAUSED) {
            return OlaStatus.PAUSED;
        }
        if (Boolean.TRUE.equals(task.getCompleted()) || task.getOlaStatus() == OlaStatus.COMPLETED) {
            return resolveBreached(task, Objects.requireNonNullElse(task.getClosedAt(), now))
                    ? OlaStatus.BREACHED
                    : OlaStatus.COMPLETED;
        }
        ZonedDateTime safeNow = Objects.requireNonNullElse(now, ZonedDateTime.now());
        if (resolveBreached(task, safeNow)) {
            return OlaStatus.BREACHED;
        }
        if (task.getOlaWarningAt() != null && !safeNow.isBefore(task.getOlaWarningAt())) {
            return OlaStatus.WARNING;
        }
        return OlaStatus.OK;
    }

    private boolean resolveBreached(Task task, ZonedDateTime moment) {
        return task != null && task.getOlaDeadline() != null && moment != null && moment.isAfter(task.getOlaDeadline());
    }

    @Transactional
    public void synchronizeWithTaskState(Task task) {
        if (task == null) {
            return;
        }
        if (Boolean.TRUE.equals(task.getCompleted())) {
            complete(task);
        } else if (Boolean.TRUE.equals(task.getFrozen())) {
            pause(task);
        }
    }


    private void finishPauseAt(Task task, ZonedDateTime now) {
        if (task == null || task.getOlaPausedAt() == null) {
            return;
        }
        boolean useWorkingTime = Boolean.TRUE.equals(task.getOlaUseWorkingTime());
        long remaining = Math.max(0L, Objects.requireNonNullElse(task.getOlaRemainingSecondsOnPause(), 0L));
        long pausedSeconds = Math.max(0L, workingTimeService.secondsBetween(task.getOlaPausedAt(), now, useWorkingTime));
        task.setOlaDeadline(workingTimeService.addSeconds(now, remaining, useWorkingTime));
        updateActiveStage(task, stage -> {
            stage.setOlaDeadline(task.getOlaDeadline());
            stage.setPausedSeconds(Objects.requireNonNullElse(stage.getPausedSeconds(), 0L) + pausedSeconds);
        });
    }


    private void closeCurrentStage(Task task, ZonedDateTime leftAt) {
        if (task == null || task.getId() == null) {
            return;
        }
        stageRepository.findFirstByTaskIdAndLeftAtIsNullOrderByEnteredAtDesc(task.getId())
                .ifPresent(stage -> closeStage(stage, leftAt));
        stageRepository.flush();
    }

    private void closeStage(TaskSupportLineStage stage, ZonedDateTime leftAt) {
        stage.setLeftAt(leftAt);
        if (stage.getOlaDeadline() == null || stage.getStatus() == OlaStatus.DISABLED) {
            stage.setStatus(OlaStatus.DISABLED);
            stageRepository.save(stage);
            return;
        }
        boolean breached = leftAt != null && leftAt.isAfter(stage.getOlaDeadline());
        stage.setStatus(breached ? OlaStatus.BREACHED : OlaStatus.COMPLETED);
        if (breached && stage.getBreachedAt() == null) {
            stage.setBreachedAt(stage.getOlaDeadline());
        }
        stageRepository.save(stage);
    }

    private void updateActiveStage(Task task, java.util.function.Consumer<TaskSupportLineStage> updater) {
        if (task == null || task.getId() == null) {
            return;
        }
        stageRepository.findFirstByTaskIdAndLeftAtIsNullOrderByEnteredAtDesc(task.getId())
                .ifPresent(stage -> {
                    updater.accept(stage);
                    stageRepository.save(stage);
                });
    }

    private String normalizeReason(String reason) {
        String value = Objects.toString(reason, "").trim();
        return value.isBlank() ? null : value;
    }
}
