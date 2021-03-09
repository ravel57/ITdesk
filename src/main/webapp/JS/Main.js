window.onload = () => {
    if (document.getElementsByClassName("client-info").length > 0) {
        let element = document.getElementById("messages");
        element.scrollTop = element.scrollHeight;
    }
}


