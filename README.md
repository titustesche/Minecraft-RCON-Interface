## Minecraft RCON Interface
This is a simple Webinterface based on a NodeJS Server to send commands to an RCON enabled Minecraft Server (or any RCON-able Server for that matter)

It is able to run locally and connect to any Server you would like.
Just make sure to enable RCON on your server and enter the corresponding **Address**, **port** and **pasword** into the app.js file.

After that, simply start the backend using
> node app.js

and open **index.html** in your browser of choice.

If everything worked correctly you should see 
> Successfully connected to backend!
> 
> [Backend] Successfully connected to rcon server!

in the console.
