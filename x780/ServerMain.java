package x780;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
class ServerMain {
    public static void main(String[] args){
        System.out.println("Server main");
        // FTP fails badly with IPv6 addresses.
        System.setProperty("java.net.preferIPv4Stack", "true");
        int port = 6780;
        if (args.length != 0) {
            System.out.println("Arg: " + args[0]);
            port = Integer.parseUnsignedInt(args[0]);
        }
        try{
            ServerSocket mainSocket = new ServerSocket(port);
            // Avoid TIME_WAIT bind error:
            // (https://hea-www.harvard.edu/~fine/Tech/addrinuse.html)
            mainSocket.setReuseAddress(true);
            System.out.println("Server accepting on port " + port);
            while (true) {
                Socket commandSocket = mainSocket.accept();
                (new Thread(new CommandHandler(commandSocket))).start();
            }
        }catch (IOException e) {
            System.out.println("IO Exception on port " + port);
        }
    }
}
