<%@ taglib prefix='c' uri='http://java.sun.com/jsp/jstl/core' %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<div id="right-side">
    <div class="right-side-column">
        <c:forEach var="client" items="${clients}">
            <a href="/dialogs/${client.id}" class="client-card">
                <div class="client-card-info" style="border-bottom: 1px solid #adb2b2">
                    <span class="organization">
                        <c:out value="${client.organization}"/>
                    </span>
                    <span class="name">
                        <c:out value="${client.firstName}"/> <c:out value="${client.lastName}"/>
                    </span>
                    <br>
                </div>
                <div class="tasks-list">
                    <p>Задача 1</p>
<%--                    <p>Задача 2</p>--%>
<%--                    <p>Задача 3</p>--%>
<%--                    <p>Задача 4</p>--%>
                </div>
                <div class=" client-card-info" style="border-top: 1px solid #adb2b2">
                    <span class=" name">
                        <fmt:formatDate value="${client.lastMessage}" pattern="dd-MMM  HH:mm"/>
                    </span>
                </div>
            </a>
        </c:forEach>
    </div>
</div>