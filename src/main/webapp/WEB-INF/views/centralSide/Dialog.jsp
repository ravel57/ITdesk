<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<html>
<jsp:include page="../meta/Head.jsp"/>
<body>
<jsp:include page="../header/Header.jsp"/>
<div style="display: flex; height: calc(100vh - 61px)">
    <jsp:include page="../leftSide/LeftSide.jsp"/>
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
                </divcla>
            </div>
        </div>
        <div class="messages">
            <div class="message client">
                <p> 56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                </p>
            </div>
            <div class="message support">
                <p> 56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                </p>
            </div>
            <div class="message client">
                <p> 56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                    56746513245641234894567465132456412348945674651324564123489456746513245641234894
                </p>
            </div>
        </div>
        <div class="reply">
            <input>
            <div class="reply send-button">▶</div>
        </div>
    </div>
    <%--    <c:out value="${id}"></c:out>--%>
</div>
</body>
</html>
