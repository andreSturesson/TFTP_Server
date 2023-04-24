import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

public class App {
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "/home/andre/Downloads/TFTP/src/assignment3/home/"; //custom address at your PC
    public static final String WRITEDIR = "/home/andre/Downloads/TFTP/src/assignment3/home/"; //custom address at your PC
    // OP codes
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;

    public static void main(String[] args) {
        if (args.length > 0) {
            System.err.printf("usage: java %s\n", App.class.getCanonicalName());
            System.exit(1);
        }
        //Starting the server
        try {
            App server = new App();
            server.start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    private void start() throws SocketException {
        byte[] buf = new byte[BUFSIZE];

        // Create socket
        DatagramSocket socket = new DatagramSocket(null);

        // Create local bind point 
        SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
        socket.bind(localBindPoint);

        System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

        // Loop to handle client requests 
        while (true) {
            final InetSocketAddress clientAddress = receiveFrom(socket, buf);

            // If clientAddress is null, an error occurred in receiveFrom()
            if (clientAddress == null)
                continue;

            final StringBuffer requestedFile = new StringBuffer();
            final int reqtype = ParseRQ(buf, requestedFile);

            new Thread() {
                public void run() {
                    try {
                        DatagramSocket sendSocket = new DatagramSocket(0);

                        // Connect to client
                        sendSocket.connect(clientAddress);

                        System.out.printf("%s request for localhost from %s using port %d\n",
                            (reqtype == OP_RRQ) ? "Read" : "Write",
                            clientAddress.getHostName(), clientAddress.getPort());

                        // Read request
                        if (reqtype == OP_RRQ) {
                            requestedFile.insert(0, READDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
                        }
                        // Write request
                        else {
                            requestedFile.insert(0, WRITEDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), OP_WRQ);
                        }
                        sendSocket.close();
                        System.out.println("CLOSED sendsocket");
                    } catch (SocketException e) {
                        e.printStackTrace();
                    }
                }
            }.start();
        }
    }

    /**
     * Reads the first block of data, i.e., the request for an action (read or write).
     * @param socket (socket to read from)
     * @param buf (where to store the read data)
     * @return socketAddress (the socket address of the client)
     */
    private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {
        // Create datagram packet
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        // Receive packet
        try {
            socket.receive(packet);
            System.out.println(packet.getData().toString());
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            System.out.println("fel");
        }
        // Get client address and port from the packet
        System.out.println("Adress:" + packet.getAddress() + " Port: " + packet.getPort());
        InetSocketAddress sockAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
        //return socketAddress;
        return sockAddress;
    }

    /**
     * Parses the request in buf to retrieve the type of request and requestedFile
     * 
     * @param buf (received request)
     * @param requestedFile (name of file to read/write)
     * @return opcode (request type: RRQ or WRQ)
     */
    private int ParseRQ(byte[] buf, StringBuffer requestedFile) {
        // See "TFTP Formats" in TFTP specification for the RRQ/WRQ request contents

        int i = 2;
        while (buf[i] != 0) {
            requestedFile.append((char) buf[i]);
            i++;
        }
        System.out.println(requestedFile);
        return buf[1];
    }

    /**
     * Handles RRQ and WRQ requests 
     * 
     * @param sendSocket (socket used to send/receive packets)
     * @param requestedFile (name of file to read/write)
     * @param opcode (RRQ or WRQ)
     */
    private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {
        // See "TFTP Formats" in TFTP specification for the DATA and ACK packet contents
        File file = new File(requestedFile);
        if (opcode == OP_RRQ) {
            if (file.exists() && file.isFile()) {
                send_ERR(sendSocket, 6);
                System.err.println("File exists");
            }
            byte[] bytes = null;
            try {
                bytes = Files.readAllBytes(Paths.get(requestedFile));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

			//Preparing packet for transmission
            ByteBuffer prepare = ByteBuffer.allocate(bytes.length + 4);
            prepare.putShort((short) OP_DAT); //For data
            prepare.putShort((short) 1); //Block is always 1 because this TFTP does not support more packets over 512 bytes
            prepare.put(bytes); //DATA
            byte[] data = prepare.array();

            //Create packet for transmission
            DatagramPacket packet = new DatagramPacket(data, data.length);
            boolean result = send_DATA_receive_ACK(sendSocket, packet);


        } else if (opcode == OP_WRQ) {
            if (file.exists() && file.isFile()) {
                send_ERR(sendSocket, 6);
                System.err.println("File exists");
            }

            try (FileOutputStream fileOutputStream = new FileOutputStream(requestedFile)) {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

				//Preparing first ACK
                ByteBuffer prepare = ByteBuffer.allocate(4);
                prepare.putShort((short) OP_ACK);
                prepare.putShort((short) 0);
                byte[] ack = (prepare.array());

                receive_DATA_send_ACK(sendSocket, ack, byteArrayOutputStream);

				//Preparing second ACK after file recived
                ByteBuffer prepare1 = ByteBuffer.allocate(4);
                prepare1.putShort((short) OP_ACK);
                prepare1.putShort((short) 1);
                byte[] ack1 = (prepare1.array());
                DatagramPacket acks = new DatagramPacket(ack1, ack1.length);
				//Send second ack and finalize put transmission
                sendSocket.send(acks);
                fileOutputStream.write(byteArrayOutputStream.toByteArray());
                fileOutputStream.flush();
                fileOutputStream.close();
                byteArrayOutputStream.close();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                send_ERR(sendSocket, 2);
                e.printStackTrace();
            }
        } else {
            System.err.println("Invalid request. Sending an error packet.");
            // See "TFTP Formats" in TFTP specification for the ERROR packet contents
            // send_ERR(params);
            return;
        }
    }

    /**
    To be implemented
    */
    private boolean send_DATA_receive_ACK(DatagramSocket sendSocket, DatagramPacket data) {
        try {
            sendSocket.send(data);
            byte[] ackData = new byte[BUFSIZE];

            try {
                DatagramPacket ack = new DatagramPacket(ackData, ackData.length);
                sendSocket.receive(ack);
                return true;

            } catch (SocketTimeoutException te) {
                return false;
            }
        } catch (IOException e) {
            send_ERR(sendSocket, 2);
            return false;
        }
    }

    private boolean receive_DATA_send_ACK(DatagramSocket sendSocket, byte[] ack, ByteArrayOutputStream output) {
        try {
            sendSocket.send(new DatagramPacket(ack, ack.length));
            byte[] bytes = new byte[BUFSIZE];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            sendSocket.receive(packet);
            if (ParseRQ(bytes, new StringBuffer()) == OP_DAT) {
                byte[] data = Arrays.copyOfRange(packet.getData(), 4, packet.getLength());
                output.write(data);
                output.flush();
            }
        } catch (IOException e) {
            send_ERR(sendSocket, 2);
            return false;
        }
        return true;

    }

    private void send_ERR(DatagramSocket sendSocket, int errorCode) {
        String error = "";
        if (errorCode == 1) {
            error = "File not found";
        }
        if (errorCode == 2) {
            error = "Access violation.";
        }
        if (errorCode == 6) {
            error = " File already exists.";
        }
        byte[] data = new byte[error.length() + 5];
        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        byteBuffer.putShort((short) OP_ERR);
        byteBuffer.putShort((short) errorCode);
        byteBuffer.put(error.getBytes());
        byteBuffer.put((byte) 0);

        try {
            sendSocket.send(new DatagramPacket(data, data.length));
        } catch (IOException e) {
            System.err.printf("Cannot send ERROR: " + e);
        }
    }

}