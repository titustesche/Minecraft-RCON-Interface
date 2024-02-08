const Rcon = require('rcon-client').Rcon;
const { Server } = require('socket.io');

const host = '127.0.0.1';
const port = 25575;
const password = '1234';

// Funktion zum senden von Befehlen
async function sendCommand(cmd, callback)
{
    // Initialisiere rcon Verbindung
    const rcon = new Rcon({host, port, password});
    // Versuche mit dem rcon Server zu verbinden und den Befehl zu senden
    try
    {
        await rcon.connect();
        const response = await rcon.send(cmd);
        await rcon.end();
        // Gebe die Server Antwort zurück
        console.log(response  + " in function");
        callback(response);
    }

    // Falls das fehlschlägt, gebe den Fehler zurück
    catch (error)
    {
        callback(error);
    }
}

const io = new Server({
    cors: {
        origin: "*",
    }
});

io.on("connection", (socket) => {
    console.log("Client connected");
    socket.on('request', (cmd) => {
        sendCommand(cmd, function (reply) {
            socket.emit('reply', reply);
        });
    })
})

io.listen(3000, () => {
    console.log('listening on port 3000');
});