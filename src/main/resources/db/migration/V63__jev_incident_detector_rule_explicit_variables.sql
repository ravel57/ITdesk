-- Правило «Детектор массовых инцидентов (Jev)» раньше было одним непрозрачным действием jev.detectIncident():
-- окно, число клиентов, порог «это сбой», пауза и режим лежали в отдельных настройках Jev, а серьёзность,
-- смягчение порога при тревоге мониторинга и интервалы обновлений были зашиты в Java. Теперь это обычные
-- condition/action-ноды: числа и условия меняются прямо в редакторе правила (свои пороги и окна для отдельных
-- организаций, другая схема при срабатывании). Каждая переменная задаётся в самом графе записью
-- var.имя = jev.метод(...) / incident.метод(...), а шаги получают всё нужное аргументами.
--
-- Значения — стандартные: окно 15 мин, 3 клиента (2 при тревоге мониторинга), «это сбой» от 0.7, пауза 60 мин,
-- «одна проблема» от 0.6, P1 от 10 клиентов, схема «только уведомлять». Прежние значения из настроек Jev не переносятся.
--
-- Чтобы вместо уведомления создавался черновик инцидента, замените в правиле:
--   «Отметить: тот же инцидент»:  incident.attachSignal(var.outage.signalId, var.outage.matchedIncidentId, 15)
--   «Отметить: недавний инцидент»: incident.attachSignal(var.outage.signalId, var.cooldown.incidentId, 15)
--   «Проверить паузу…»: var.cooldown = incident.checkCooldown(var.outage.signalId, 60, true)
--   «Уведомить ответственных»:  var.draft = incident.createDraftFromSignals(var.outage.signalId, var.window, var.verdict, var.severity, 15, 30, 60, 120); incident.notifyResponsible(var.outage.signalId, var.window, 'Создан черновик инцидента ' + var.draft.number + '. ' + 'Массовые обращения: ' + var.outage.serviceName + ' — ' + var.outage.symptom + ': ' + var.window.clients + ' клиент(ов) за ' + var.window.windowMinutes + ' мин, серьёзность ' + var.severity + ', вероятная причина — ' + var.verdict.cause + '. Проверьте и объявите инцидент.')
update automation_trigger
set description         = 'Jev размечает входящие сообщения; правило считает похожие сбои от разных клиентов в окне и при пороге уведомляет ответственных или создаёт черновик инцидента. Окно, пороги, паузу и схему меняйте нодами правила.',
    workflow_definition = $json$
{
  "version": 2,
  "entryNodeId": "inc-event",
  "nodes": [
    {"id": "inc-event", "type": "EVENT", "label": "Событие", "x": 80.0, "y": 300.0, "config": {"triggerType": "MESSAGE_INCOMING"}},
    {"id": "inc-analyze", "type": "ACTION", "label": "Jev: разметка сообщения", "x": 380.0, "y": 300.0, "config": {"actionScript": "var.outage = jev.analyzeOutage()", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-answer", "type": "CONDITION", "label": "Jev ответил?", "x": 700.0, "y": 300.0, "config": {"expression": "var.outage.notNull()", "groups": [], "manualExpression": true}},
    {"id": "inc-c-matched", "type": "CONDITION", "label": "Относится к открытому инциденту?", "x": 1020.0, "y": 300.0, "config": {"expression": "var.outage.matchedIncidentId.notNull()", "groups": [], "manualExpression": true}},
    {"id": "inc-attach-matched", "type": "ACTION", "label": "Отметить: тот же инцидент", "x": 1340.0, "y": 60.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'MATCHED_INCIDENT', var.outage.matchedIncidentId)", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-outage", "type": "CONDITION", "label": "Это сбой с понятным сервисом?", "x": 1340.0, "y": 300.0, "config": {"expression": "var.outage.serviceId.notNull() && var.outage.probability >= 0.7", "groups": [], "manualExpression": true}},
    {"id": "inc-ignore", "type": "ACTION", "label": "Не считать", "x": 1660.0, "y": 540.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'IGNORED')", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-count", "type": "ACTION", "label": "Посчитать обращения в окне", "x": 1660.0, "y": 300.0, "config": {"actionScript": "var.window = incident.countSignals(var.outage.signalId, 15)", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-threshold", "type": "CONDITION", "label": "Достаточно клиентов?", "x": 1980.0, "y": 300.0, "config": {"expression": "var.window.clients >= 3 || ((var.outage.monitoringStatus = 'WARNING' || var.outage.monitoringStatus = 'CRITICAL') && var.window.clients >= 2)", "groups": [], "manualExpression": true}},
    {"id": "inc-counted", "type": "ACTION", "label": "Учесть, порог не достигнут", "x": 2300.0, "y": 540.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'COUNTED')", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-cooldown", "type": "ACTION", "label": "Проверить паузу после срабатывания", "x": 2300.0, "y": 300.0, "config": {"actionScript": "var.cooldown = incident.checkCooldown(var.outage.signalId, 60, false)", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-recent-incident", "type": "CONDITION", "label": "Недавний инцидент по этой проблеме?", "x": 2620.0, "y": 300.0, "config": {"expression": "var.cooldown.incidentId.notNull()", "groups": [], "manualExpression": true}},
    {"id": "inc-attach-recent", "type": "ACTION", "label": "Отметить: недавний инцидент", "x": 2940.0, "y": 60.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'MATCHED_INCIDENT', var.cooldown.incidentId)", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-cooldown", "type": "CONDITION", "label": "Пауза ещё идёт?", "x": 2940.0, "y": 300.0, "config": {"expression": "var.cooldown.active", "groups": [], "manualExpression": true}},
    {"id": "inc-in-cooldown", "type": "ACTION", "label": "Пауза", "x": 3260.0, "y": 540.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'COOLDOWN')", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-verify", "type": "ACTION", "label": "Jev: проверка пачки", "x": 3260.0, "y": 300.0, "config": {"actionScript": "var.verdict = jev.verifyBatch(var.outage.signalId, var.window)", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-verified", "type": "CONDITION", "label": "Проверка выполнена?", "x": 3580.0, "y": 300.0, "config": {"expression": "var.verdict.notNull()", "groups": [], "manualExpression": true}},
    {"id": "inc-unverified", "type": "ACTION", "label": "Учесть без проверки", "x": 3580.0, "y": 540.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'COUNTED')", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-c-same", "type": "CONDITION", "label": "Одна проблема?", "x": 3900.0, "y": 300.0, "config": {"expression": "var.verdict.sameProblem >= 0.6", "groups": [], "manualExpression": true}},
    {"id": "inc-rejected", "type": "ACTION", "label": "Разные проблемы", "x": 3900.0, "y": 540.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'BATCH_REJECTED')", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-severity", "type": "SET_VARIABLE", "label": "Серьёзность по оценке Jev", "x": 4220.0, "y": 300.0, "config": {"name": "severity", "expression": "var.verdict.severity"}},
    {"id": "inc-c-p1", "type": "CONDITION", "label": "P1 при малом числе клиентов?", "x": 4540.0, "y": 300.0, "config": {"expression": "var.severity = 'P1' && var.window.clients < 10", "groups": [], "manualExpression": true}},
    {"id": "inc-cap", "type": "SET_VARIABLE", "label": "Не выше P2", "x": 4540.0, "y": 60.0, "config": {"name": "severity", "expression": "'P2'"}},
    {"id": "inc-trigger", "type": "ACTION", "label": "Уведомить ответственных", "x": 4860.0, "y": 300.0, "config": {"actionScript": "incident.markSignal(var.outage.signalId, 'TRIGGERED'); incident.notifyResponsible(var.outage.signalId, var.window, 'Обнаружен массовый сбой. ' + 'Массовые обращения: ' + var.outage.serviceName + ' — ' + var.outage.symptom + ': ' + var.window.clients + ' клиент(ов) за ' + var.window.windowMinutes + ' мин, серьёзность ' + var.severity + ', вероятная причина — ' + var.verdict.cause + '. Инцидент не создан (в правиле выбрано «только уведомлять»).')", "actions": [], "continueOnError": false, "manualScript": true}},
    {"id": "inc-end", "type": "END", "label": "Завершение", "x": 5180.0, "y": 300.0, "config": {"result": "Сценарий завершён"}}
  ],
  "edges": [
    {"id": "edge-1", "source": "inc-event", "target": "inc-analyze", "sourceHandle": "next"},
    {"id": "edge-2", "source": "inc-analyze", "target": "inc-c-answer", "sourceHandle": "next"},
    {"id": "edge-3", "source": "inc-c-answer", "target": "inc-c-matched", "sourceHandle": "true"},
    {"id": "edge-4", "source": "inc-c-answer", "target": "inc-end", "sourceHandle": "false"},
    {"id": "edge-5", "source": "inc-c-matched", "target": "inc-attach-matched", "sourceHandle": "true"},
    {"id": "edge-6", "source": "inc-c-matched", "target": "inc-c-outage", "sourceHandle": "false"},
    {"id": "edge-7", "source": "inc-attach-matched", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-8", "source": "inc-c-outage", "target": "inc-count", "sourceHandle": "true"},
    {"id": "edge-9", "source": "inc-c-outage", "target": "inc-ignore", "sourceHandle": "false"},
    {"id": "edge-10", "source": "inc-ignore", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-11", "source": "inc-count", "target": "inc-c-threshold", "sourceHandle": "next"},
    {"id": "edge-12", "source": "inc-c-threshold", "target": "inc-cooldown", "sourceHandle": "true"},
    {"id": "edge-13", "source": "inc-c-threshold", "target": "inc-counted", "sourceHandle": "false"},
    {"id": "edge-14", "source": "inc-counted", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-15", "source": "inc-cooldown", "target": "inc-c-recent-incident", "sourceHandle": "next"},
    {"id": "edge-16", "source": "inc-c-recent-incident", "target": "inc-attach-recent", "sourceHandle": "true"},
    {"id": "edge-17", "source": "inc-c-recent-incident", "target": "inc-c-cooldown", "sourceHandle": "false"},
    {"id": "edge-18", "source": "inc-attach-recent", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-19", "source": "inc-c-cooldown", "target": "inc-in-cooldown", "sourceHandle": "true"},
    {"id": "edge-20", "source": "inc-c-cooldown", "target": "inc-verify", "sourceHandle": "false"},
    {"id": "edge-21", "source": "inc-in-cooldown", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-22", "source": "inc-verify", "target": "inc-c-verified", "sourceHandle": "next"},
    {"id": "edge-23", "source": "inc-c-verified", "target": "inc-c-same", "sourceHandle": "true"},
    {"id": "edge-24", "source": "inc-c-verified", "target": "inc-unverified", "sourceHandle": "false"},
    {"id": "edge-25", "source": "inc-unverified", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-26", "source": "inc-c-same", "target": "inc-severity", "sourceHandle": "true"},
    {"id": "edge-27", "source": "inc-c-same", "target": "inc-rejected", "sourceHandle": "false"},
    {"id": "edge-28", "source": "inc-rejected", "target": "inc-end", "sourceHandle": "next"},
    {"id": "edge-29", "source": "inc-severity", "target": "inc-c-p1", "sourceHandle": "next"},
    {"id": "edge-30", "source": "inc-c-p1", "target": "inc-cap", "sourceHandle": "true"},
    {"id": "edge-31", "source": "inc-c-p1", "target": "inc-trigger", "sourceHandle": "false"},
    {"id": "edge-32", "source": "inc-cap", "target": "inc-trigger", "sourceHandle": "next"},
    {"id": "edge-33", "source": "inc-trigger", "target": "inc-end", "sourceHandle": "next"}
  ]
}
$json$
where name = 'Детектор массовых инцидентов (Jev)';

-- Настройки детектора переехали в правило
alter table app_settings
    drop column if exists jev_incident_window_minutes,
    drop column if exists jev_incident_min_clients,
    drop column if exists jev_incident_outage_threshold,
    drop column if exists jev_incident_cooldown_minutes,
    drop column if exists jev_incident_mode,
    drop column if exists jev_incident_p1_min_clients;
