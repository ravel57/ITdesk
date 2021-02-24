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
        <div contenteditable="true" aria-multiline="true" class="input" style="width: 100%">

        </div>
        <div class="reply send-button">
            <span class="icon">send</span>
            <%--            ▶--%>
        </div>
    </div>
</div>
<div class="check-boxes">
    <form method="post" action="input5.php">
        <p><b>Задачи</b></p>
        <p>
            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 1</label><br>
            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 2</label><br>
            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 3</label><br>
            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 4</label><br>
            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 5</label><br>
        </p>
        <%--        <p><input type="submit" value="Отправить"></p>--%>
    </form>
</div>
<%--    <c:out value="${id}"></c:out>--%>
