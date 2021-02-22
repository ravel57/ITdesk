<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<html>
<jsp:include page="meta/Head.jsp"/>
<body>
<jsp:include page="header/Header.jsp"/>
<div style="display: flex; height: calc(100% - 41px);">
    <jsp:include page="menu/LeftSide.jsp"/>
    <c:choose>
        <c:when test="${currentBlock eq 'Tasks'}">
            <jsp:include page="centralSide/Tasks.jsp"/>
        </c:when>
        <c:when test="${currentBlock eq 'Dialog'}">
            <jsp:include page="centralSide/Dialog.jsp"/>
        </c:when>
    </c:choose>
</div>
</body>
<%--<jsp:include page="meta/Scripts.jsp"/>--%>
</html>
