<%@ taglib prefix='c' uri='http://java.sun.com/jsp/jstl/core' %>

<div class="dialog">
    <div class="client-info">
        <div class="client">
            <span>Имя</span>
            <span>Организация</span>
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
                <p><c:out value="${message}"/></p>
            </div>
        </c:forEach>
    </div>
    <div class="reply">
        <input>
        <div class="reply send-button">▶</div>
    </div>
</div>
<%--    <c:out value="${id}"></c:out>--%>
