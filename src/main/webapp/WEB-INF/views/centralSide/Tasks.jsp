<%@ taglib prefix='c' uri='http://java.sun.com/jsp/jstl/core' %>
<div id="right-side">
    <div class="right-side-column">
        <c:forEach var="client" items="${clients}">
            <a href="/dialogs/${client.id}" class="right-side-component">
                <c:out value="${client.firstName}"/> <c:out value="${client.lastName}"/>
                <br><uh></uh>
                <c:out value="${client.organization}"/>
            </a>
        </c:forEach>
    </div>
</div>