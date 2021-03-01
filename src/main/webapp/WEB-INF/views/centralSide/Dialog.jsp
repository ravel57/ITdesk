<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix='c' uri='http://java.sun.com/jsp/jstl/core' %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<% pageContext.setAttribute("newLineChar", "\\n"); %>

<div class="dialog">
    <div class="client-info">
        <div style="display: flex">
            <div>
                <div class="client" data-id="<c:out value="${client.id}"/>">
                    <p>
                        <c:out value="${client.firstName}"/> <c:out value="${client.lastName}"/>
                    </p>
                    <p style="font-size: 0.7em; margin-bottom: 6px;">
                        <c:out value="${client.organization}"/>
                    </p>
                </div>
                <div style="display: flex">
                    <div class="client-field">
                        Кастомное поле 1
                    </div>
                    <div class="client-field">
                        Кастомное поле 2
                    </div>
                    <div class="client-field">
                        Кастомное поле 3
                    </div>
                    <div class="client-field">
                        Кастомное поле 4
                    </div>
                </div>
            </div>
            <div style="margin-left: auto; margin-right: 10px; color: #000; margin-top: auto;" class="icon">
                search
            </div>
        </div>
        <%--        </div>--%>
    </div>
    <div class="messages">
        <c:forEach var="message" items="${messages}">
            <div class="message client" id="${message.id}">
                    <%--                <c:out value="${message}"/>--%>
<%--                <p><c:out value="${fn:replace(message, '\\\n', '<br/>')}"/></p>--%>
                <p>
                    <c:out value="${fn:replace(message.text, newLineChar, '<br />')}"/>
                </p>
            </div>
        </c:forEach>
    </div>
    <div class="reply">
        <div contenteditable="true" aria-multiline="true" class="input" style="width: 100%">

        </div>
        <div class="reply send-button">
            <span class="icon" style="color: #fff">send</span>
<%--            <input type='button' value='Submit'>--%>
            <%--            ▶--%>
        </div>
    </div>
</div>
<div class="check-boxes">
        <p><b>Задачи</b></p>
        <div class="check-box">
            <p>Задача 1</p>
            <span style="margin-left: auto; margin-right: 3px;" class="hide">x</span>
        </div>
<%--        <p>--%>
<%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 1</label><br>--%>
<%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 2</label><br>--%>
<%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 3</label><br>--%>
<%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 4</label><br>--%>
<%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 5</label><br>--%>
<%--        </p>--%>
        <%--        <p><input type="submit" value="Отправить"></p>--%>
    </form>
</div>
<%--    <c:out value="${id}"></c:out>--%>
