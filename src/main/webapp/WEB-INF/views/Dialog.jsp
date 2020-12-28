<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<html>
<jsp:include page="meta/Head.jsp"/>
<body>
<jsp:include page="header/Header.jsp"/>
<div style="display: flex; height: calc(100vh - 61px)">
    <jsp:include page="leftSide/LeftSide.jsp"/>
    <c:out value="${id}"></c:out>
</div>
</body>
</html>
