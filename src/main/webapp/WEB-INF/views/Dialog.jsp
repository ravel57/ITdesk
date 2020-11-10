<%--
  Created by IntelliJ IDEA.
  User: petya
  Date: 10.11.2020
  Time: 0:11
  To change this template use File | Settings | File Templates.
--%>

<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<html>
<jsp:include page="meta/Head.jsp"/>
<body>
<jsp:include page="header/Header.jsp"/>
<c:out value="${id}"></c:out>
</body>
</html>
