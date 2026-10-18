-- Пороги уверенности Jev по автоматизациям вместо одного общего jev_min_confidence.
-- Текущий общий порог переносится во все автоматизации, кроме разбиения на заявки (там строже: лишняя заявка дороже).
alter table if exists app_settings
    add column if not exists jev_thresholds jsonb;

update app_settings
set jev_thresholds = jsonb_build_object(
        'classifyTask', jev_min_confidence,
        'setPriority', jev_min_confidence,
        'createTaskIfNeeded', jev_min_confidence,
        'splitTasks', greatest(jev_min_confidence, 0.75),
        'suggestKnowledgeArticle', jev_min_confidence,
        'checkIncidentRecovery', jev_min_confidence,
        'selectSupportLine', jev_min_confidence,
        'escalateIfNeeded', jev_min_confidence,
        'findDuplicate', jev_min_confidence,
        'detectIncident', jev_min_confidence
    )
where jev_thresholds is null;

alter table if exists app_settings
    alter column jev_thresholds set default '{}'::jsonb,
    alter column jev_thresholds set not null,
    drop column if exists jev_min_confidence;
