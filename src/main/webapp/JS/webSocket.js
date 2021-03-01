var stompClient = null


function setConnected(connected) {
    $("#connect").prop("disabled", connected)
    $("#disconnect").prop("disabled", !connected)
    if (connected) {
        $("#conversation").show()
    } else {
        $("#conversation").hide()
    }
    $("#greetings").html("")
}

function connect() {
    var socket = new SockJS('/gs-guide-websocket')
    stompClient = Stomp.over(socket)
    stompClient.connect({}, function (frame) {
        setConnected(true)
        console.log('Connected: ' + frame)
        // stompClient.subscribe('/topic/messages', function (greeting) {
        //     showMessage(JSON.parse(greeting.body).content)
        // })
        stompClient.subscribe('/topic/messages', callback)
    })

    if (document.getElementsByClassName("client-info").length > 0) {
        let textarea = document.getElementsByClassName('input')[0]
        textarea.addEventListener('keypress', (e) => {
            // if (e.keyCode === 13) {
            if (e.ctrlKey) {
                sendMessage()
            }
            // }
        });
    }
}

// function disconnect() {
//     if (stompClient !== null) {
//         stompClient.disconnect()
//     }
//     setConnected(false)
//     console.log("Disconnected")
// }

function sendMessage() {
    stompClient.send("/app/messagesa", {}, JSON.stringify({
            'text': document.getElementsByClassName("input")[0].innerText,
            'clientId': document.querySelector("div[data-id]").getAttribute("data-id"),
            'supportId': 1,
            'date': new Date()
        })
    )
    var iDiv = document.createElement('div');
    iDiv.className = 'message support';
    var innerDiv = document.createElement('p');
    innerDiv.append(document.getElementsByClassName("input")[0].innerText)
    iDiv.appendChild(innerDiv);
    document.getElementsByClassName('messages')[0].appendChild(iDiv);
    let element = document.getElementsByClassName("messages")[0];
    element.scrollTop = element.scrollHeight;
    document.getElementsByClassName("input")[0].innerText = ""
}

function callback(message) {
    if (document.getElementsByClassName("client-info").length > 0) {
        var iDiv = document.createElement('div');
        iDiv.className = 'message client';
        var innerDiv = document.createElement('p');
        innerDiv.append(JSON.parse(message.body).text)
        iDiv.appendChild(innerDiv);
        document.getElementsByClassName('messages')[0].appendChild(iDiv);
        let element = document.getElementsByClassName("messages")[0];
        element.scrollTop = element.scrollHeight;
    } else {
        document.location.reload();
    }
}

$(function () {
    $("form").on('submit', function (e) {
        e.preventDefault()
    })
    connect()
    $(".send-button").click(function () {
        sendMessage()
    })
})


