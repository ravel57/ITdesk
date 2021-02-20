<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<html>
<jsp:include page="meta/Head.jsp"/>
<body>
<jsp:include page="header/Header.jsp"/>
<div style= "display: flex; height: calc(100% - 41px);">
    <jsp:include page="menu/LeftSide.jsp"/>
    <jsp:include page="centralSide/Tasks.jsp"/>
</div>
<jsp:include page="meta/Scripts.jsp"/>
</body>
</html>
