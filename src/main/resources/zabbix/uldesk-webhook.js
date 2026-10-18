// ULDesk: отправка событий Zabbix по контракту uldesk.zabbix.v1
var p = JSON.parse(value);

// Zabbix оставляет нераскрытые макросы как есть, например «{EVENT.RECOVERY.DATE}» в событии о проблеме
function v(x) {
    return !x || /^\{[A-Z0-9_.$#]+\}$/.test(x) ? null : x;
}

if (!v(p.uldesk_url) || !v(p.uldesk_token)) {
    throw 'Не заданы параметры uldesk_url и uldesk_token';
}

var status = p.event_update_status === '1' ? 'UPDATE' : (p.event_value === '1' ? 'PROBLEM' : 'RESOLVED');
var tags = [];
if (v(p.event_tags)) {
    try {
        tags = JSON.parse(p.event_tags);
    } catch (error) {
        Zabbix.log(4, '[ULDesk] Не удалось разобрать теги: ' + error);
    }
}
var zabbixUrl = v(p.zabbix_url) ? p.zabbix_url.replace(/\/+$/, '') : null;

var body = {
    schema: 'uldesk.zabbix.v1',
    event: {
        id: p.event_id,
        status: status,
        name: v(p.event_name),
        severity: parseInt(p.event_nseverity, 10),
        severityName: v(p.event_severity),
        startedAt: v(p.event_date) ? p.event_date + ' ' + p.event_time : null,
        recoveredAt: v(p.event_recovery_date) ? p.event_recovery_date + ' ' + p.event_recovery_time : null,
        acknowledged: p.event_ack === 'Yes',
        opdata: v(p.event_opdata),
        tags: tags,
        url: zabbixUrl && v(p.trigger_id) ? zabbixUrl + '/tr_events.php?triggerid=' + p.trigger_id + '&eventid=' + p.event_id : null
    },
    trigger: { id: v(p.trigger_id), description: v(p.trigger_description) },
    host: { id: v(p.host_id), host: v(p.host_host), name: v(p.host_name), ip: v(p.host_ip), groups: v(p.host_groups) },
    update: { action: v(p.event_update_action), message: v(p.event_update_message), user: v(p.event_update_user) }
};

var request = new HttpRequest();
request.addHeader('Content-Type: application/json');
request.addHeader('X-ULDesk-Token: ' + p.uldesk_token);
if (v(p.http_proxy)) {
    request.setProxy(p.http_proxy);
}

var response = request.post(p.uldesk_url, JSON.stringify(body));
var code = request.getStatus();
Zabbix.log(4, '[ULDesk] HTTP ' + code + ': ' + response);
if (code < 200 || code >= 300) {
    throw 'ULDesk ответил HTTP ' + code + ': ' + response;
}

var result = {};
try {
    result = JSON.parse(response);
} catch (error) {
    // Ответ без JSON не мешает: событие уже принято
}
return JSON.stringify({ tags: result.tags || {} });
