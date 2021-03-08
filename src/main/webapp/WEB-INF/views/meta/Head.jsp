<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<head>
    <meta charset="utf-8"/>
    <title>Task desk</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/CSS/Clients.css"/>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/CSS/Dialog.css"/>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/CSS/LeftSide.css"/>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/CSS/Header.css"/>
    <link href="https://fonts.googleapis.com/icon?family=Material+Icons" rel="stylesheet">
<%--    <link href="/webjars/bootstrap/css/bootstrap.min.css" rel="stylesheet">--%>
<%--    <link href="/main.css" rel="stylesheet">--%>
<%--    <script src="/webjars/jquery/jquery.min.js"></script>--%>
<%--    <script src="/webjars/sockjs-client/sockjs.min.js"></script>--%>
    <script src="https://code.jquery.com/jquery-3.3.1.min.js" integrity="sha256-FgpCb/KJQlLNfOu91ta32o/NMZxltwRo8QtmkMRdAu8=" crossorigin="anonymous"></script>
<%--    <script src="/webjars/stomp-websocket/stomp.min.js"></script>--%>
    <script src="https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>
<%--    <script src="https://code.jquery.com/ui/1.12.1/jquery-ui.js"></script>--%>
    <script src="https://unpkg.com/vue@next"></script>
    <script src="${pageContext.request.contextPath}/JS/Main.js"></script>
    <script src="${pageContext.request.contextPath}/JS/webSocket.js"></script>
    <script src="${pageContext.request.contextPath}/JS/Tasks.js"></script>
    </head>