<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<html>
<head>
    <meta charset="utf-8"/>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/CSS/index.css"/>
    <title>Task desk</title>
</head>
<body>
<header></header>
<div class="main-frame">
    <div id="left-side">
        <!--        <div class="left-side-menu-container">-->
        <table id="filters">
            <tr>
                <td class="filter_button"><img src="/images/toList.png" style="transform: scale(0.85)"></td>
                <td class="filter_button"><img src="/images/viewSettings.png" style="transform: scale(1.1)"></td>
                <td class="filter_button"><img src="/images/search.png" style="transform: scale(0.8)"></td>
                <td class="filter_button">
                    <a href="/"><img src="/images/refresh.png" style="transform: scale(1.25)"></a>
                </td>
            </tr>
        </table>
        <div class="left-side-menu-component"></div>
        <div class="left-side-menu-component"></div>
        <div class="left-side-menu-component"></div>
        <div class="left-side-menu-component"></div>
        <div class="left-side-menu-component"></div>
        <div class="left-side-menu-component"></div>
        <!--        </div>-->
    </div>
    <div id="right-side">
        <div class="right-side-body">
            <div class="right-side-column">
                <c:forEach var="dialogID" items="${dialogs}">
                    <a href="/dialog/${dialogID}" class="right-side-component"> <c:out value="${dialogID}"/> </a>
                </c:forEach>
            </div>
            <div class="right-side-column">
                <c:forEach var="dialogID" items="${dialogs}">
                    <a href="/dialog/${dialogID}" class="right-side-component"> <c:out value="${dialogID}"/> </a>
                </c:forEach>
            </div>
        </div>
    </div>
</div>
</body>
</html>
