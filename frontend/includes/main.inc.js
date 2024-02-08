// Try to connect to the Node websocket
const socket = io('http://127.0.0.1:3000');

// If the connection was established, notify the user
socket.on("connect", () => {
    responseOutput.textContent = responseOutput.textContent + "Successfully connected to backend! \n";
})

// Get control elements from the page
const sendBtn = document.getElementById('sendButton');
const commandInput = document.getElementById('commandInput');
const responseOutput = document.getElementById('responseOutput');

// Output any messages received from the reply event to the user
socket.on('reply', (reply) => {
    responseOutput.textContent = responseOutput.textContent + reply + "\n";
})

// Emit the request event with the contents of the input field
sendBtn.addEventListener('click', () => {
    socket.emit('request', commandInput.value);
})