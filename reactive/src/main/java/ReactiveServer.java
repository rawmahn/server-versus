
import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by player on 11-Nov-15.
 */
public class ReactiveServer {

//    private static Logger log = LoggerFactory.getLogger(ReactiveServer.class);

    public static void log(String s) {
        //nnop
//            System.out.println(s);
    }


    public static class Workflow {

        long tick = new Date().getTime();

        public static final int CAPACITY = 100;

        public ReentrantLock lock = new ReentrantLock();

        private String incomingString = "";

        SocketChannel clientChannel;

        ByteBuffer incoming = ByteBuffer.allocate(CAPACITY);
        ByteBuffer outgoing = ByteBuffer.allocate(2048);

        /**
         * Check if we can expect progress
         *
         * @param selectionKey
         * @return
         */
        public boolean looksGood(SelectionKey selectionKey) {
            boolean b = (state.equals(State.READING) && selectionKey.isReadable())
                    || (state.equals(State.WRITING) && selectionKey.isWritable());

//            if (!b)
//                log("does not look good!");
            return b;
        }

        enum State {
            READING,
            PROCESSING,
            WRITING
        }

        boolean readyToWrite;

        State state = State.READING;

        public void handle(SelectionKey next) throws IOException {
            if (state.equals(State.READING)) {
                if (next.isReadable()) {
                    int read = clientChannel.read(incoming);

                    if (read > 0) {
                        log(getId() + "bytes read " + read);
                        incoming.flip();
                        byte[] dst = new byte[incoming.remaining()];
                        incoming.get(dst);
                        String str = new String(dst, StandardCharsets.UTF_8);
//                        log(str);
                        incomingString += str;
                        incoming.clear();

                        if (incomingString.endsWith("\r\n")) {
                            log(getId() + "EOM! Processing...");
                            String ret = doSomeCDPUIntensiveProcessingOp(incomingString);
                            outgoing = ByteBuffer.wrap(ret.getBytes());
                            state = State.WRITING;
                            incomingString = "";
                        }

                    } else if (read == -1) {

                        log(getId() + " EO Input - closing channel");
                        clientChannel.close();

                    }
                }


            } else if (state.equals(State.WRITING))
                if (next.isWritable()) {
                    int written = clientChannel.write(outgoing);
                    log(getId() + " written " + written + " bytes");

                    if (!outgoing.hasRemaining()) {
                        clientChannel.close();
                        log(getId() + "Request served in " + (new Date().getTime() - tick) / 1000 + "sec");
                        log(getId() + "===========================================================");
                        log("");
                    }

                }
        }

        private String getId() {
            return this.toString() + "(" + clientChannel + ")" + " - ";
        }

        public Workflow(SocketChannel clientChannel) {
            this.clientChannel = clientChannel;


        }

        public String doSomeCDPUIntensiveProcessingOp(String input) {


            MessageDigest instance;
            try {
                instance = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            instance.update(input.getBytes());
            byte[] digest = instance.digest();
            byte[] encode = Base64.getEncoder().encode(digest);


            String html = "Your request:<br/>" +
                    input +
                    "<br/>" +
                    "Your sha1: " + new String(encode) + "<br/>" +
                    "Some random as well:" + new Random().nextFloat();


            for (int i=0;i<10;i++)
                html+=html;


            String response = "HTTP/1.1 200 OK\n" +
                    "Date: Mon, 27 Jul 2009 12:28:53 GMT\n" +
                    "Server: Apache/2.2.14 (Win32)\n" +
                    "Last-Modified: Wed, 22 Jul 2009 19:15:56 GMT\n" +
                    "Content-Length: " + html.length() + "\n" +
                    "Content-Type: text/html\n" +
                    "\r\n" +
                    html;

            return response;
        }


        public void sendResponse() {
//            clientSocketChannel.write(Charset.defaultCharset()
//                    .encode(buffer));
        }


    }


    public static void main(String[] args) throws IOException {


        Selector allClientChannels;
        ServerSocketChannel server;

        allClientChannels = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(true);
        server.socket().bind(new java.net.InetSocketAddress(80));

        int workers = 1;

        for (int i=0;i<workers;i++){
            new WorkerThread(allClientChannels).start();
        }

        try {

            while (true) {

                SocketChannel clientChannel = server.accept();
                clientChannel.configureBlocking(false);
                Workflow att = new Workflow(clientChannel);
                clientChannel.register(allClientChannels, SelectionKey.OP_READ | SelectionKey.OP_WRITE, att);
                log("Registered " + att + "(" + clientChannel + ")");
            }

        } finally {
        }
    }


    private static class WorkerThread extends Thread {

        private Selector selector;

        public WorkerThread(Selector selector) {
            this.selector = selector;
        }

        @Override
        public void run() {


            while (true) {
                Workflow chosenWF = null;
                try {
                    selector.selectNow();
                    if (Thread.interrupted()) {
                        log(Thread.currentThread().getId() + " interrupted ");
                        return;
                    }

                    SelectionKey selectionKey = null;
                    synchronized (ReactiveServer.class) {
                        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                        Workflow attachment = null;
                        while (iterator.hasNext()) {
                            selectionKey = iterator.next();

                            attachment = (Workflow) selectionKey.attachment();
                            if (attachment.lock.tryLock()) {
                                if (attachment.looksGood(selectionKey)) {
                                    chosenWF = attachment;
                                    iterator.remove();
                                    break;
                                }
                            } else {
                                log("Oh no,no lock!");
                            }
                        }
                    }
                    if (chosenWF != null) {
                        chosenWF.handle(selectionKey);
                    }
//                    else
//                        Thread.sleep(1);


                } catch (IOException e) {
//                    e.printStackTrace();
//                } catch (InterruptedException e) {
//                    log("Interrupted worker");
//                    return;
                } finally {
                    if (chosenWF != null)
                        chosenWF.lock.unlock();
                }

            }
        }

    }
}
