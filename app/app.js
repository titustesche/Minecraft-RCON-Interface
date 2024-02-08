// Modules needed for functionality
const Rcon = require('rcon-client').Rcon;
const { Server } = require('socket.io');


// Login credentials for the rcon server
const host = '127.0.0.1';
const port = 25575;
const password = '1234';

// Function to send commands to rcon server
async function sendCommand(cmd, callback)
{
    // initialize rcon connection
    const rcon = new Rcon({host, port, password});
    // Try to connect to the rcon server, send the command and callback the server's reply
    try
    {
        await rcon.connect();
        const response = await rcon.send(cmd);
        await rcon.end();
        // Gebe die Server Antwort zurück
        console.log(response  + " in function");
        callback("" + response);
    }

    // If that fails, reply with the given error message
    catch (error)
    {
        callback("" + error);
    }
}

// Create new websocket using Socket.io
const io = new Server({
    cors: {
        origin: "*",
    }
});

// Event receiver for handling communication between user and server
io.on("connection", (socket) => {
    console.log("Client connected");
    // Send an empty test command to the server to test if it is reachable
    sendCommand('', (function (reply) {
        if (reply.indexOf('Error') > -1)
        {
            // Throw an error when the connection fails
            socket.emit('reply', '[Backend] Error while trying to connect to host: ' + reply);
        }

        else
        {
            // Notify the user when a connection can be established
            socket.emit('reply', '[Backend] Successfully connected to rcon server!');
        }
    }));
    // Handling the request event
    socket.on('request', (cmd) => {
        // When the request Event is triggered, send the command over to the rcon Server, wait for it to respond and pass the reply back to the frontend
        sendCommand(cmd, function (reply) {
            socket.emit('reply', reply);
        });
    })
})


// Listen for incoming connections
io.listen(3000);