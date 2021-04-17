<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<html>
<jsp:include page="meta/Head.jsp"/>
<body>
<c:choose>
    <c:when test="${currentBlock eq 'Clients'}">
        <jsp:include page="header/Header.jsp"/>
        <div style="display: flex; height: calc(100% - 41px);">
            <jsp:include page="menu/LeftSide.jsp"/>
                <%--            <link rel="stylesheet" href="${pageContext.request.contextPath}/CSS/Clients.css"/>--%>
            <jsp:include page="centralSide/Clients.jsp"/>
        </div>
    </c:when>
    <c:when test="${currentBlock eq 'Dialog'}">
        <%--            <jsp:include page="centralSide/Dialog.jsp"/>--%>
        <div id="app"></div>
        <div id="client" client="${client}"></div>
        <div id="impmsg" messages="${messages}"></div>
        <div id="inptsks" tasks="${tasks}"></div>
        <script src="/dist/js/app.js"></script>
        <script src="/dist/js/chunk-vendors.js"></script>
    </c:when>
</c:choose>
</body>
<%--<jsp:include page="meta/Scripts.jsp"/>--%>
</html>
