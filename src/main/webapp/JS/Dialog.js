const dialogApp = {
    data() {
        return {
            messages: [],
            messageText: '',
        }
    },
    methods: {
        sendMessage() {
            if (this.messageText > '') {
                if (sendMessageWS(this.messageText)) {
                    let element = document.getElementById("messages")
                    let h = element.scrollHeight
                    this.messages.push({
                        id: this.messages.length,
                        messageType: "message support",
                        supportId: 0,
                        text: this.messageText
                    })
                    setTimeout(() => { if (element.scrollTop + element.clientHeight +300 >= h) {
                        element.scrollTop = element.scrollHeight
                    }}, 20)
                    // console.log(element.scrollHeight)
                    this.messageText = ''
                }
            }
        },
        messageHandlerEnter(e) {
            if (e.ctrlKey)
                this.sendMessage()
            // element.scrollTop = element.scrollHeight;
        },
    },
    mounted: function () {
        let lmessages = JSON.parse(this.$refs.messages.getAttribute('messages')
            .replaceAll('\'', '\"'))
        this.messages = lmessages
        // console.log(this.$refs.messages)
    },
    // computed{
    //
    // },
    // watch{
    //
    // }
}

Vue.createApp(dialogApp).mount('#dialog')

// import "./webSocket"
// import sendMessageWS from "webSocket"
