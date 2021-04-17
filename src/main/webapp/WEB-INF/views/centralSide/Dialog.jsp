<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix='c' uri='http://java.sun.com/jsp/jstl/core' %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<% pageContext.setAttribute("newLineChar", "\\n"); %>

<div id="dialog">
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
    </div>

    <div id="messages"
         messages="${messages}"
         ref="messages"
    >
        <div class="message"
             v-for="message in messages"
             :class="message.messageType"
        >
            {{message.text}}
        </div>
        <%--        <c:forEach var="message" items="${messages}">--%>
        <%--            <div class="${message.messageType}" id="${message.id}">--%>
        <%--                    &lt;%&ndash;                <c:out value="${message}"/>&ndash;%&gt;--%>
        <%--                    &lt;%&ndash;                <p><c:out value="${fn:replace(message, '\\\n', '<br/>')}"/></p>&ndash;%&gt;--%>
        <%--                <p>--%>
        <%--                    <c:out value="${fn:replace(message.text, newLineChar, '<br />')}"/>--%>
        <%--                </p>--%>
        <%--            </div>--%>
        <%--        </c:forEach>--%>
    </div>
    <div class="reply">
        <%--        <div contenteditable="true"--%>
        <%--             aria-multiline="true"--%>
        <%--             class="input" style="width: 100%"--%>
        <%--             v-model="messageText"--%>
        <%--        >        </div>--%>
        <textarea class="input-textarea"
                  v-model="messageText"
                  placeholder="Сообщение"
                  @keydown.enter="messageHandlerEnter"
                  type="text"
        ></textarea>
        <div class="reply send-button">
            <span class="icon" style="color: #fff" @click="sendMessage">send</span>
        </div>
    </div>
</div>

<div id="check-boxes"
     tasks="${tasks}"
     ref="task"
>
    <p style="margin: 5px 10px"><b>Задачи</b></p>
    <div style="padding: 0 10px;">
<%--    <div style="display: flex; justify-content: center">--%>
        <textarea type="text"
               v-model="newTaskStr"
               placeholder="Новая задача"
               @keydown.enter="handleEnter"
                  class="task-input"
        ></textarea>
        <button
                v-on:click="addCheckBox"
                style="display: block; width: 100%; margin-top: 3px;"
        >добавить
        </button>
    </div>

    <div class="check-box"
         v-for="(checkBox, i) in checkBoxes"
         v-bind:class="[checkBox.actual ? '' : 'closed']"
    >
        {{ checkBox.text }}
        <span v-text="checkBox.actual ? 'x' : '+'"
              @click="chengeTaskStatus(i)"
              class="task-button"
        ></span>
    </div>
    <%--    <div class="check-box">--%>
    <%--            <p>Задача 1</p>--%>
    <%--            <span style="margin-left: auto; margin-right: 3px;" class="hide">x</span>--%>
    <%--        </div>--%>
    <%--        <p>--%>
    <%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 1</label><br>--%>
    <%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 2</label><br>--%>
    <%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 3</label><br>--%>
    <%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 4</label><br>--%>
    <%--            <label><input type="checkbox" name="checkbox" value="value" style="margin: 10px">Задача 5</label><br>--%>
    <%--        </p>--%>
    <%--        <p><input type="submit" value="Отправить"></p>--%>
</div>
<%--<script src="${pageContext.request.contextPath}/JS/ws.js"></script>--%>
<%--<script src="${pageContext.request.contextPath}/JS/webSocket.js"></script>--%>
<%--<script src="${pageContext.request.contextPath}/JS/Dialog.js"></script>--%>
<%--<script src="${pageContext.request.contextPath}/JS/Tasks.js"></script>--%>
<script src="${pageContext.request.contextPath}/JS/Main.js"></script>
<%--    <c:out value="${id}"></c:out>--%>
