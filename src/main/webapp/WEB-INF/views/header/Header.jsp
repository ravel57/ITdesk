<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<header>
    <span onclick="menu()" style="cursor: pointer; user-select: none;">&#9776;Меню</span>

    <script>
        function menu() {
            if (document.getElementById("left-side").style.display === "") {
                document.getElementById("left-side").style.display = "none";
            } else {
                document.getElementById("left-side").style.display = "";
            }
        }

    </script>

</header>