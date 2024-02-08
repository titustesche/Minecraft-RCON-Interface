const socket = io('http://127.0.0.1:3000');

socket.on("connect", () => {
    responseOutput.textContent = responseOutput.textContent + "Successfully connected to backend! \n";
})

const sendBtn = document.getElementById('sendButton');
const commandInput = document.getElementById('commandInput');
const responseOutput = document.getElementById('responseOutput');

socket.on('reply', (reply) => {
    //console.log(reply);
    responseOutput.textContent = responseOutput.textContent + reply + "\n";
})

sendBtn.addEventListener('click', () => {
    socket.emit('request', commandInput.value);
    //responseOutput.textContent = responseOutput.textContent + "Sent: " + commandInput.value + "\n";
})