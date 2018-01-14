package x780;

import java.net.InetAddress;
import java.io.BufferedReader;
import java.net.Socket;
import java.io.PrintWriter;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.Scanner;
import java.net.UnknownHostException;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.io.OutputStream;
import java.io.InputStream;

import static java.lang.System.err;

class FtpException extends Exception {
    FtpException(String s){
        super(s);
    }
    FtpException(String s, Throwable cause){
        super(s, cause);
    }
}
class LoggedStream {
    private PrintStream ps;
    public void println(String s){
        err.println("> " + s);
        ps.println(s);
    }
    LoggedStream(OutputStream os, boolean t){
        ps = new PrintStream(os, t);
    }
}
/*
   All data transfers are preceeded by a PASV.  This prevents problems
   with stupid NATs that don't have an FTP protocol processor.

   Ascii mode for LIST, Binary mode for data transfers.

 */
class ClientMain implements Runnable {
    InetAddress serverAddr;
    int serverPort;

    Socket commandSocket;
    Scanner commandSocketIn;
    LoggedStream commandSocketOut;
    String commandResponseLine;
    static Pattern pasvAddrPortPattern =
        Pattern.compile("\\d{1,3},\\d{1,3},\\d{1,3},\\d{1,3},\\d{1,3},\\d{1,3}");

/**
   Extract the addr and port from pasvResult and connect to it.

   For extraction see: https://cr.yp.to/ftp/retr.html

   @return connected socket or null.
 */
    Socket dataConnect() throws FtpException {
        commandSocketOut.println("PASV");
        checkedResult();
        String pasvResult = commandResponseLine;
        err.println("crl: '" + commandResponseLine + "'");
        Matcher m = pasvAddrPortPattern.matcher(commandResponseLine);
        if (!m.find()) {
            throw new FtpException("PASV response parse error");
        }
        String [] split = pasvResult.substring(m.start(), m.end()).split(",");
        String addr = split[0] + '.' + split[1] + '.' + split[2] + '.' + split[3];
        int port = Integer.parseInt(split[4]) * 256 +
                   Integer.parseInt(split[5]);
        Socket sock = null;
        try{
            sock = new Socket(addr, port);
        }catch (IOException e) {
            throw new FtpException("data connection error", e);
        }
        return sock;
    }

/*
   PASV, connect, LIST, read data, close streams, print socket data, check result.
 */
    void doList() throws FtpException {
        Socket dataSocket = dataConnect();

        commandSocketOut.println("LIST");
        StringBuilder sb = new StringBuilder();
        byte [] buffer = new byte[4096];
        try{
            try (InputStream in = dataSocket.getInputStream()) {
                int len;
                while ((len = in.read(buffer)) > 0) {
                    sb.append(new String(buffer, 0, len));
                }
            }
        }catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(sb.toString());
        checkedResult();
    }

/*
   PASV, connect, STOR, transfer, close streams.
 */
    void doGet(String fileName) throws FtpException {
        Socket dataSocket = dataConnect();

        commandSocketOut.println("RETR " + fileName);
        byte[] buffer = new byte[8192];
        try{
            try (FileOutputStream fout = new FileOutputStream(fileName);
                 InputStream in = dataSocket.getInputStream()) {
                int len;
                while ((len = in.read(buffer)) > 0) {
                    fout.write(buffer, 0, len);
                }
            }
        }catch (IOException e) {
            throw new FtpException("io error on data stream", e);
        }
        checkedResult();
    }

/*
   PASV, connect, RETR, transfer, close streams.
 */
    void doPut(String fileName) throws FtpException {
        Socket dataSocket = dataConnect();

        commandSocketOut.println("STOR " + fileName);
        byte[] buffer = new byte[8192];
        try{
            try (OutputStream os = dataSocket.getOutputStream();
                 InputStream is = new FileInputStream(fileName)) {
                int len;
                while ((len = is.read(buffer)) > 0) {
                    os.write(buffer, 0, len);
                }
            }
        }catch (IOException e) {
            e.printStackTrace();
            throw new FtpException("io error on data stream", e);
        }
        checkedResult();
    }

    boolean handleCommand(String line) throws
    FtpException {
        err.println("handleCommand: " + line);
        String[] split = line.trim().split("\\s+");
        if (split.length < 1) {
            return true;
        }
        switch (split[0].toLowerCase()) {
        case "get":
            doGet(split[1]);
            break;
        case "put":
            doPut(split[1]);
            break;
        case "delete":
            commandSocketOut.println("DELE " + split[1]);
            getResult();
            break;
        case "ls":
            doList();
            break;
        case "cd":
            if (split[1] == "..") {
                commandSocketOut.println("CDUP");
            }else{
                commandSocketOut.println("CWD " + split[1]);
            }
            getResult();
            break;
        case "mkdir":
            commandSocketOut.println("MKD " + split[1]);
            getResult();
            break;
        case "pwd":
            commandSocketOut.println("PWD");
            getResult();
            break;
        case "quit":
            /** The spec doesn't say we have to wait or clean up any
             * previous commands.
             */
            return false;

        default:
            System.out.println("Unknown command: " + split[0]);
        }
        return true;
    }
/**
   Reads from commandSocketIn until we have a result. Decode and return.
 */
    int getResult() {
        err.println("gr start");
        String line = commandSocketIn.nextLine();
        err.println("csi nextline: " + line);
        String [] split = line.trim().split("\\s+");
        int result;
        boolean continuation = false;
        if (split[0].length() == 4 && split[0].charAt(3) == '-') {
            split[0] = split[0].substring(1, 4);
            continuation = true;
            err.println("continuation true");
        }
        result = Integer.parseUnsignedInt(split[0]);
        while (continuation) {
            line = commandSocketIn.nextLine();
            err.println("cont line: " + line);
            split = line.trim().split("\\s+");
            int result2 = Integer.parseUnsignedInt(split[0]);
            if (result2 == result) {
                continuation = false;
            }
        }
        commandResponseLine = line;
        return result;
    }

    void checkedResult() throws FtpException {
        int r = getResult();

        while (r >= 100 && r < 200) {
            r = getResult();
        }
        if (r < 200 || r >= 300) {
            throw new FtpException(commandResponseLine);
        }
    }
/**
   To test this client with real ftp servers, we need to act like a
   real client. Pull out the initial ready response and send an
   anonymous login.
 */
    void sayHi() throws IOException, FtpException {
        checkedResult();
        commandSocketOut.println("USER ftp");
        getResult();
        commandSocketOut.println("PASS myftp");
        checkedResult();
        //        commandSocketOut.println("TYPE I");
        //        checkedResult();
    }

    void setUp(){
        try{
            if (commandSocket != null) {
                commandSocket.close();
            }
            commandSocket = new Socket(serverAddr, serverPort);
            commandSocketIn = new Scanner(commandSocket.getInputStream());
            commandSocketOut = new LoggedStream(commandSocket.getOutputStream(), true);
            sayHi();
        }catch (Exception e) {
            System.out.println("Exception during setup: " + e);
            System.exit(1);
        }
    }
    @Override
    public void run(){
        String command;
        Scanner in = new Scanner(System.in);

        System.out.print("ftp# ");
        while (in.hasNextLine()) {
            command = in.nextLine();
            try{
                if (!handleCommand(command)) {
                    break;
                }
            }catch (FtpException e) {
                System.out.println(e);
                setUp();
                System.out.print("ftp# ");
                continue;
            }
            System.out.println("Server response: " + commandResponseLine);
            System.out.print("ftp# ");
        }

    }

    ClientMain(InetAddress addr, int port){
        serverAddr = addr;
        serverPort = port;
        setUp();
    }

    public static void main(String[] args){
        System.out.println("Client main");
        for (String a: args) {
            System.out.println("arg: " + a);
        }
        if (args.length != 2) {
            System.out.println("Usage: myftp <addr> <port>");
            System.exit(1);
        }
        InetAddress addr = null;
        try{
            addr = InetAddress.getByName(args[0]);
        }catch (UnknownHostException e) {
            System.out.println("Bad hostname: " + args[0]);
            System.exit(1);
        }
        (new ClientMain(addr,
                        Integer.parseUnsignedInt(args[1]))).run();
    }
}
