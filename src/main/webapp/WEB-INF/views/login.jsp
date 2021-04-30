<%@page language="java" contentType="text/html" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:th="https://www.thymeleaf.org"
      xmlns:sec="https://www.thymeleaf.org/thymeleaf-extras-springsecurity3">
<head>
    <title>login</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/login.css"/>
    <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=Ubuntu">

</head>
<body>
<div class="login-page">
    <div class="form">
        <form class="login-form" th:action="@{/login}" method="post">
            <div><label> Логин: <input type="text" name="username" placeholder="Логин"/> </label></div>
            <div><label> Пароль: <input type="password" name="password" placeholder="Пароль"/> </label></div>
            <button type="submit" value="Sign In">Войти</button>
        </form>
    </div>
</div>
<%--<form th:action="@{/login}" method="post">--%>
<%--    <div><label> User Name : <input type="text" name="username"/> </label></div>--%>
<%--    <div><label> Password: <input type="password" name="password"/> </label></div>--%>
<%--    <div><input type="submit" value="Sign In"/></div>--%>
<%--</form>--%>
<%--<img src="https://lh3.googleusercontent.com/proxy/n3ze95uz2gY_G7eOVE0MY1m9nli8xDa38HdOu3lDvSAHud57kbHal6W2DAhmSpOjaTBRF7n_prB-wwEXSoHoqZ91f9IZ">--%>
</body>
</html>