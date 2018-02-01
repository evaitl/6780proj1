# CSCI6780 (Dist Systems) Project 1

## Introduction

While this readme can be read with a text editor, it is a markdown
document that is best viewed at the github
[site](https://github.com/evaitl/6780proj1)

The code here is our solution to the first programming
[project](./docs/Programming-Project1.pdf) in CSCI6780.  It closely
follows the ftp protocol. With minor changes, either the client or the
server could interact with a standard ftp daemon (or act as an ftp
daemon). This implementation is written in Java 8.



## Design

The client is a single thread.  `ClientMain.main()` grabs the program
arguments, creates a `ClientMain` object and calls its `run()`
method. The `ClientMain` constructor connects to the server on a
command socket.  The `ClientMain.run()` method reads lines from stdin
and hands them off to a `handleCommand()` method to parse and process
commands from the user.


The server is multi threaded. The `ServerMain.main()` creates a
`ServerSocket` and loops while calling `accept()`. For every new
client connection, the loop starts a new thread for a `CommandHandler`

The server side `CommandHandler` processes a number of FTP commands on
the command Socket: MKD, RMD, CDUP, USER, PASV, ACCT, QUIT, CWD, DELE,
PWD, LIST, RETR, STOR. Each of these operates roughly as described in
[RFC959](https://tools.ietf.org/html/rfc959). Several commands that
aren't necessary for this project , like USER, PASS, and ACCT, are really
just no-ops that return positive status codes.

GET, LIST, and STOR transfers only work with a previous PASV
command. In part because it is easier to just support passive
connections rather than both passive and active connections, in part
because active connections often have problems with firewalls. 


## Build instructions

Type "make". This should create myftp and myftpserver shell scripts
that reference the created client.jar and server.jar. The myftp shell
script looks like:

```bash
#!/bin/sh
java -jar client.jar $@
```


Please type "make indent" before checking in if you do a lot of changes. If
you would like different formatting, please edit the docs/uncrustify.cfg. 


## Disclaimer

This project was done in its entirely by Eric Vaitl and Ankita
Joshi. We hereby state that we have not received unauthorized help of
any form.
