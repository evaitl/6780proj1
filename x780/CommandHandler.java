package x780;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.net.InetAddress;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.net.ServerSocket;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.Scanner;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Paths;
import java.io.UncheckedIOException;

import static java.lang.System.err;

class CommandHandler implements Runnable {
    Socket commandSocket;
    ServerSocket pasvListener;
    int clientPort;
    InetAddress clientAddr;
    PrintStream out;
    Path cwd;

    String createPasvSocket(){
        try {
            if (pasvListener != null) {
                pasvListener.close();
            }
        }catch (IOException e) {} // don't care

        try {
            /*
               Create a new socket on an ephemeral port for each PASV
               command. Just in case the client is doing something strange
               like connecting from the same port each time.
             */
            pasvListener = new ServerSocket(0);
        }catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder sb = new StringBuilder();
        /*
           Use the command socket instead of the server socket, because
           the server socket could be on INADDR_ANY, which I can't send
           to the client. Assume the client can connect to the address
           that it must currently be connected to (on the
           commandSocket).
         */
        sb.append(commandSocket.getLocalAddress().toString().replace('.', ','));
        sb.append(",");
        int port = pasvListener.getLocalPort();
        err.println("port: " + port);
        sb.append(port / 256);
        sb.append(",");
        sb.append(port % 256);

        err.println("addr: " + sb.toString());
        return sb.toString();
    }

    /**
       I don't see how to get setuid, setgid, or sticky bits in
       Java. Just ignore for now.
     */
    static String posix2string(Set<PosixFilePermission> ps){
        char [] perms = "---------".toCharArray();

        if (ps.contains(PosixFilePermission.OWNER_READ)) {
            perms[0] = 'r';
        }
        if (ps.contains(PosixFilePermission.OWNER_WRITE)) {
            perms[1] = 'w';
        }
        if (ps.contains(PosixFilePermission.OWNER_EXECUTE)) {
            perms[2] = 'x';
        }

        if (ps.contains(PosixFilePermission.GROUP_READ)) {
            perms[3] = 'r';
        }
        if (ps.contains(PosixFilePermission.GROUP_WRITE)) {
            perms[4] = 'w';
        }
        if (ps.contains(PosixFilePermission.GROUP_EXECUTE)) {
            perms[5] = 'x';
        }

        if (ps.contains(PosixFilePermission.OTHERS_READ)) {
            perms[6] = 'r';
        }
        if (ps.contains(PosixFilePermission.OTHERS_WRITE)) {
            perms[7] = 'w';
        }
        if (ps.contains(PosixFilePermission.OTHERS_EXECUTE)) {
            perms[8] = 'x';
        }

        return new String(perms);
    }
    static char fType(PosixFileAttributes attrs){
        // Not standard, but I dont' want to check for pipes, fifos, sockets, devices, etc.
        char ret = '?';

        if (attrs.isSymbolicLink()) {
            ret = 'l';
        }else if (attrs.isDirectory()) {
            ret = 'd';
        }else if (attrs.isRegularFile()) {
            ret = '-';
        }else if (attrs.isOther()) {
            ret = 'o';
        }
        return ret;
    }
    static String ls(Path p) throws IOException {
        StringBuffer sb = new StringBuffer();
        // Only needs to work on Linux, so posix should be OK.
        PosixFileAttributes attrs =
            Files.getFileAttributeView(p,
                                       PosixFileAttributeView.class)
            .readAttributes();

        sb.append(fType(attrs));
        sb.append(posix2string(attrs.permissions()) + " ");
        sb.append(attrs.owner().getName() + "\t");
        sb.append(attrs.group().getName() + "\t");
        sb.append(Files.size(p) + "\t");
        sb.append(p.getName(p.getNameCount() - 1));
        sb.append('\n');
        return sb.toString();
    }
    void doList(){
        try{
            if (pasvListener == null) {
                out.println("423 passive transfers only");
                return;
            }
            try (Socket dataSocket = pasvListener.accept()) {
                pasvListener.close();
                pasvListener = null;
                out.println("150 Data connection established");
                StringBuffer sb = new StringBuffer();
                try (DirectoryStream<Path> d = Files.newDirectoryStream(cwd)) {
                    for (Path e: d) {
                        sb.append(ls(e));
                    }
                }
                dataSocket.getOutputStream().write(sb.toString().getBytes());
            }
            out.println("226 All sent");
        }catch (IOException e) {
            out.println("455 some exception");
        }
    }

    void doRetr(Path fp){
        if (pasvListener == null) {
            out.println("423 passive transfers only");
            return;
        }
        if (!Files.exists(fp)) {
            out.println("453 Couldn't find file");
            return;
        }
        if (Files.isDirectory(fp)) {
            out.println("454 Can't retr dir");
            return;
        }
        Socket sock;
        try{
            sock = pasvListener.accept();
            pasvListener.close();
            pasvListener = null;
        }catch (IOException e) {
            out.println("455 accept exception");
            return;
        }
        try (OutputStream os = sock.getOutputStream();
             InputStream is = Files.newInputStream(fp)) {
            out.println("150 Data connection established");
            byte[] buffer = new byte[8096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }catch (Exception e) {
            out.println("424 Something went wrong");
            return;
        }
        out.println("226 file sent");
    }
    void doStor(Path fp){
        if (pasvListener == null) {
            out.println("453 passive transfers only");
            return;
        }
        if (!Files.isDirectory(fp.getParent())) {
            out.println("457 parent not directory");
            return;
        }
        Socket sock;
        try{
            sock = pasvListener.accept();
            pasvListener.close();
            pasvListener = null;
        }catch (IOException e) {
            out.println("455 IO exception");
            return;
        }
        try (InputStream is = sock.getInputStream();
             OutputStream os = Files.newOutputStream(fp)) {
            out.println("150 Data connection established");
            byte[] buffer = new byte[8096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }catch (IOException e) {
            out.println("424 Something went wrong");
            return;
        }
        out.println("226 file received");
    }

/**
   @return false if we are done, else true.
 */
    private boolean handleCommand(String line){
        // Strip, split, find command and execute with args.
        String[] split = line.trim().split("\\s+");
        if (split.length < 1) {
            return true;
        }
        switch (split[0].toLowerCase()) {
        case "pasv":
            out.println("227 Entering passive mode (" +
                        createPasvSocket() + ")");
            break;
        case "user":
            out.println("230 nice to meet you");
            break;
        case "pass":
            out.println("202 Not necessary");
            break;
        case "acct":
            out.println("202 Not necessary");
            break;
        case "quit":
            out.println("221 buh-bye");
            return false;

        case "mkd":
        {
            Path p = cwd.resolve(split[1]).normalize().toAbsolutePath();
            try{
                Files.createDirectory(p);
                out.println("257 dir created");
            }catch (Exception e) {
                out.println("451 dir not created");
            }
        }
        break;
        case "rmd":
        {
            Path p = cwd.resolve(split[1]).normalize().toAbsolutePath();
            if (!Files.isDirectory(p)) {
                out.println("455 " + split[1] + "not a directory");
            }else{
                try{
                    Files.delete(p);
                    out.println("255 dir " + split[1] + " removed");
                }catch (IOException e) {
                    out.println("456 couldn't remove dir " + split[1]);
                }
            }
        }
        break;
        case "cdup":
            cwd = cwd.resolve("..").normalize().toAbsolutePath();
            out.println("250 mov'n on up");
            break;
        case "cwd":
            if (Files.isDirectory(cwd.resolve(split[1]))) {
                cwd = cwd.resolve(split[1]).normalize().toAbsolutePath();
                out.println("250 sure thing");
            }else{
                out.println("451 No can do");
            }
            break;
        case "dele":
        {
            Path p = cwd.resolve(split[1]).normalize().toAbsolutePath();
            if (!Files.exists(p)) {
                out.println("453 file " + split[1] + "doesn't exist?");
            }else if (Files.isDirectory(p)) {
                out.println("451 " + split[1] + "is a directory");
            }else{
                try{
                    Files.delete(p);
                }catch (Exception e) {
                    out.println("452 Couldn't delete for some reason");
                    break;
                }
                out.println("250 deleted");
            }
        }
        break;
        case "pwd":
            out.println("257 " + cwd);
            break;
        case "list":
            doList();
            break;
        case "retr":
            doRetr(cwd.resolve(split[1]).normalize().toAbsolutePath());
            break;
        case "stor":
            doStor(cwd.resolve(split[1]).normalize().toAbsolutePath());
            break;
        case "noop":
            out.println("200 nothing");
            break;
        default:
            out.println("500 I don't know '" + split[0] + "'");
        }
        return true;
    }


    public void run(){
        System.out.println("Running Command Handler: " + commandSocket);
        System.out.println("cwd: " + cwd);
        try (Scanner in = new Scanner(commandSocket.getInputStream());
             PrintStream out = new PrintStream(commandSocket.getOutputStream())) {
            String line;
            this.out = out;
            clientPort = commandSocket.getPort();
            clientAddr = commandSocket.getInetAddress();
            out.println("200 Let's do this");
            while (in.hasNextLine()) {
                line = in.nextLine();
                System.out.println("Command line read: " + line);
                if (!handleCommand(line)) {
                    break;
                }
            }

        }catch (IOException e) {
            System.out.println("IO Exception in command handler" + e);
        }finally{
            try{
                commandSocket.close();
                commandSocket = null;
            }catch (IOException e) {} // don't care
        }
        System.out.println("Commands done.");
    }

    CommandHandler(Socket sock){
        commandSocket = sock;
        cwd = Paths.get(".").normalize().toAbsolutePath();
    }

}
