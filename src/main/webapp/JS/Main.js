window.onload = () => {
    if (document.getElementsByClassName("client-info").length > 0) {
        let element = document.getElementsByClassName("messages")[0];
        element.scrollTop = element.scrollHeight;
    }
}


