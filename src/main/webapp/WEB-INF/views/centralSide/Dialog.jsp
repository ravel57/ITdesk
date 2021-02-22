<%@ taglib prefix='c' uri='http://java.sun.com/jsp/jstl/core' %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>

<div class="dialog">
    <div class="client-info">
        <div class="client">
            <c:out value="${client.firstName}"/> <c:out value="${client.lastName}"/>
            <br>
            <span style="font-size: 0.7em;"><c:out value="${client.organization}"/></span>
        </div>
        <br>
        <div class="client-fields">
            <span>Кастомное поле 1</span>
            <span>Кастомное поле 2</span>
            <span>Кастомное поле 3</span>
            <span>Кастомное поле 4</span>
        </div>
    </div>
    <div class="messages">
        <c:forEach var="message" items="${messages}">
            <div class="message client">
<%--                <c:out value="${message}"/>--%>
                <p><c:out value="${fn:replace(message, '\\\n', '<br/>')}"/></p>
            </div>
        </c:forEach>
    </div>
    <div class="reply">
        <div contenteditable="true" aria-multiline="true" class = "input" style="width: 100%">

        </div>
        <div class="reply send-button">
            <span class="icon">send</span>
<%--            ▶--%>
        </div>
    </div>
</div>
<%--    <c:out value="${id}"></c:out>--%>
