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
                    <c:forEach var="task" items="${client.tasks}">
                        <p>${task.text}</p>
                    </c:forEach>
                </div>
                <div class=" client-card-info date" style="border-top: 1px solid #adb2b2">
                    <span class=" date">
                        <fmt:formatDate value="${client.lastMessage}" pattern="dd-MMM  HH:mm"/>
                    </span>
                </div>
            </a>
        </c:forEach>
    </div>
</div>