const tasksApp = {
    data() {
        return {
            newTaskStr: '',
            checkBoxes: [{}],
            // active: false,
            // upHere: false,
            // data: null
        }
    },
    methods: {
        addCheckBox() {
            console.log("new task: ", this.newTaskStr)
            if (this.newTaskStr > '') {
                this.checkBoxes.push({text: this.newTaskStr, actual: true})
                sendTaskWS(this.newTaskStr)
                this.newTaskStr = ''
            }
        },

        handleEnter(e) {
            if (e.ctrlKey)
                this.addCheckBox()
        },
        chengeTaskStatus(index) {
            console.log("changed: ", index)
            this.checkBoxes[index].actual = !this.checkBoxes[index].actual
            changedTaskStatusWS(index, this.checkBoxes[index].actual)
        },
    },

    mounted: function () {
        // let tasks = JSON.parse(this.$refs.tasks.getAttribute('tasks')
        this.checkBoxes = JSON.parse(document.getElementById("check-boxes").getAttribute('tasks')
            .replaceAll('\'', '\"'))
        // console.log(tasks)
    },
    // computed{
    //
    // },
    // watch{
    //
    // }
}

Vue.createApp(tasksApp).mount('#check-boxes')