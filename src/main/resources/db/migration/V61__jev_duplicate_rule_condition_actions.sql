-- "Дубли заявок и инциденты (Jev)" была одним непрозрачным действием jev.findDuplicate(), которое само решало
-- и само выполняло побочные эффекты (привязку к инциденту, комментарии клиенту) в Java. Теперь jev.findDuplicate()
-- только решает и пишет var.duplicateTaskId/duplicateIncidentId/duplicateIncidentNumber/duplicateSameClient —
-- привязку к инциденту, системную заметку и комментарий оператору делает само правило через condition/action-ноды
-- (incident.linkTask, incident.addNote, client.addInternalNote), текст сообщений редактируется в правиле, а не в коде.
-- Dollar-quoting ($json$...$json$), чтобы не экранировать кавычки скриптов внутри JSON.
update automation_trigger
set description         = 'Ищет, не дублирует ли новая заявка открытую: сначала среди заявок активных инцидентов, затем среди открытых заявок за последние 72 часа. Дубль заявки из активного инцидента привязывается к инциденту, о прочих дублях пишет оператору комментарий в чате.',
    workflow_definition = $json$
{
      "version": 2,
      "entryNodeId": "event-dup-a",
      "nodes": [
        {"id": "event-dup-a", "type": "EVENT", "label": "Событие", "x": 80.0, "y": 260.0, "config": {"triggerType": "TASK_CREATED"}},
        {"id": "action-dup-b", "type": "ACTION", "label": "Jev: решение", "x": 380.0, "y": 260.0, "config": {"actionScript": "jev.findDuplicate()", "actions": [], "continueOnError": false, "manualScript": true}},
        {"id": "condition-dup-c", "type": "CONDITION", "label": "Есть дубль?", "x": 680.0, "y": 260.0, "config": {"expression": "var.duplicateTaskId.notNull()", "groups": [], "manualExpression": true}},
        {"id": "end-dup-d", "type": "END", "label": "Завершение", "x": 980.0, "y": 420.0, "config": {"result": "Дублей не найдено"}},
        {"id": "condition-dup-e", "type": "CONDITION", "label": "Дубль из инцидента?", "x": 980.0, "y": 180.0, "config": {"expression": "var.duplicateIncidentId.notNull()", "groups": [], "manualExpression": true}},
        {"id": "action-dup-f", "type": "ACTION", "label": "Привязать к инциденту", "x": 1280.0, "y": 60.0, "config": {"actionScript": "incident.linkTask(var.duplicateIncidentId); incident.addNote('Заявка #' + task.id + ' — дубль заявки #' + var.duplicateTaskId + ' (Jev)'); client.addInternalNote('Jev: заявка #' + task.id + ' — дубль заявки #' + var.duplicateTaskId + ', привязана к инциденту ' + var.duplicateIncidentNumber)", "actions": [], "continueOnError": false, "manualScript": true}},
        {"id": "end-dup-g", "type": "END", "label": "Завершение", "x": 1580.0, "y": 60.0, "config": {"result": "Привязано к инциденту"}},
        {"id": "condition-dup-h", "type": "CONDITION", "label": "Тот же клиент?", "x": 1280.0, "y": 300.0, "config": {"expression": "var.duplicateSameClient", "groups": [], "manualExpression": true}},
        {"id": "action-dup-i", "type": "ACTION", "label": "Комментарий: тот же клиент", "x": 1580.0, "y": 220.0, "config": {"actionScript": "client.addInternalNote('Jev: заявка #' + task.id + ' похожа на открытую заявку #' + var.duplicateTaskId + ' этого же клиента — возможно, это одна проблема и заявки стоит объединить.')", "actions": [], "continueOnError": false, "manualScript": true}},
        {"id": "end-dup-j", "type": "END", "label": "Завершение", "x": 1880.0, "y": 220.0, "config": {"result": "Похожая заявка того же клиента"}},
        {"id": "action-dup-k", "type": "ACTION", "label": "Комментарий: другой клиент", "x": 1580.0, "y": 380.0, "config": {"actionScript": "client.addInternalNote('Jev: заявка #' + task.id + ' похожа на открытую заявку #' + var.duplicateTaskId + ' другого клиента. Если у нескольких клиентов одна проблема, возможно, это инцидент.')", "actions": [], "continueOnError": false, "manualScript": true}},
        {"id": "end-dup-l", "type": "END", "label": "Завершение", "x": 1880.0, "y": 380.0, "config": {"result": "Похожая заявка другого клиента"}}
      ],
      "edges": [
        {"id": "edge-dup-1", "source": "event-dup-a", "target": "action-dup-b", "sourceHandle": "next"},
        {"id": "edge-dup-2", "source": "action-dup-b", "target": "condition-dup-c", "sourceHandle": "next"},
        {"id": "edge-dup-3", "source": "condition-dup-c", "target": "end-dup-d", "sourceHandle": "false"},
        {"id": "edge-dup-4", "source": "condition-dup-c", "target": "condition-dup-e", "sourceHandle": "true"},
        {"id": "edge-dup-5", "source": "condition-dup-e", "target": "action-dup-f", "sourceHandle": "true"},
        {"id": "edge-dup-6", "source": "action-dup-f", "target": "end-dup-g", "sourceHandle": "next"},
        {"id": "edge-dup-7", "source": "condition-dup-e", "target": "condition-dup-h", "sourceHandle": "false"},
        {"id": "edge-dup-8", "source": "condition-dup-h", "target": "action-dup-i", "sourceHandle": "true"},
        {"id": "edge-dup-9", "source": "action-dup-i", "target": "end-dup-j", "sourceHandle": "next"},
        {"id": "edge-dup-10", "source": "condition-dup-h", "target": "action-dup-k", "sourceHandle": "false"},
        {"id": "edge-dup-11", "source": "action-dup-k", "target": "end-dup-l", "sourceHandle": "next"}
      ]
    }$json$
where name = 'Дубли заявок и инциденты (Jev)';
