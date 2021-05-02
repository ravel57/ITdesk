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
        // stompClient.subscribe('/topic/messages', function (greeting) {
        //     showMessage(JSON.parse(greeting.body).content)
        // })
        stompClient.subscribe('/topic/activity', callback)
    })

    // if (document.getElementsByClassName("client-info").length > 0) {
    //     let textarea = document.getElementsByClassName('input')[0]
    //     textarea.addEventListener('keypress', (e) => {
    //         // if (e.keyCode === 13) {
    //         if (e.ctrlKey) {
    //             sendMessage()
    //         }
    //         // }
    //     });
    // }
}

// function disconnect() {
//     if (stompClient !== null) {
//         stompClient.disconnect()
//     }
//     setConnected(false)
//     console.log("Disconnected")
// }

// function sendMessageWS(text) {
//     stompClient.send("/app/messages", {}, JSON.stringify({
//             'text': text, //document.getElementsByClassName("input")[0].innerText,
//             'clientId': document.querySelector("div[data-id]").getAttribute("data-id"),
//             'supportId': 1, //
//             'date': new Date()
//         })
//     )
//     return true
// }

// function sendTaskWS(text) {
//     stompClient.send("/app/newTask", {}, JSON.stringify({
//             'text': text,
//             'clientId': document.querySelector("div[data-id]").getAttribute("data-id"),
//         })
//     )
//     return true
// }

// function changedTaskStatusWS(id, status) {
//     stompClient.send("/app/tasksStat", {}, JSON.stringify({
//             'id': id,
//             'clientId': document.querySelector("div[data-id]").getAttribute("data-id"),
//             'text': '',
//             'actual': status,
//         })
//     )
//     return true
// }


function callback(message) {
    document.location.reload();
}

// connect()

$(function () {
    $("form").on('submit', function (e) {
        e.preventDefault()
    })
    connect()
    // $(".send-button").click(function () {
    //     sendMessage()
    // })
})


