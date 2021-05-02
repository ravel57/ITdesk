<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<html>
<jsp:include page="meta/Head.jsp"/>
<body>
<div id="app"></div>
<c:choose>
    <c:when test="${currentBlock eq 'Clients'}">
        <div id="clients" clients="${clients}"></div>
<%--        <jsp:include page="header/Header.jsp"/>--%>
<%--        <div style="display: flex; height: calc(100% - 41px);">--%>
<%--            <jsp:include page="menu/LeftSide.jsp"/>--%>
<%--            <jsp:include page="centralSide/Clients.jsp"/>--%>
<%--            <script src="https://code.jquery.com/jquery-3.3.1.min.js" integrity="sha256-FgpCb/KJQlLNfOu91ta32o/NMZxltwRo8QtmkMRdAu8=" crossorigin="anonymous"></script>--%>
<%--            <script src="https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js"></script>--%>
<%--            <script src="https://cdnjs.cloudflare.com/ajax/libs/stomp.js/2.3.3/stomp.min.js"></script>--%>
<%--            <script src="${pageContext.request.contextPath}/js/webSocket.js"></script>--%>
<%--        </div>--%>
    </c:when>
    <c:when test="${currentBlock eq 'Dialog'}">
        <div id="client" client="${client}"></div>
        <div id="impmsg" messages="${messages}"></div>
        <div id="inptsks" tasks="${tasks}"></div>
    </c:when>
</c:choose>
<script src="/dist/js/app.js"></script>
<script src="/dist/js/chunk-vendors.js"></script>
</body>
<%--<jsp:include page="meta/Scripts.jsp"/>--%>
</html>
