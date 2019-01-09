import kotlin.reflect.jvm.internal.impl.util.Check;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class Server
{
    private static final int MTU = 1500; //Bytes per Packet
    private static DatagramSocket serverSocket;

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
        {
            System.out.println("Required arguments: port");
            return;
        }

        int port = Integer.parseInt(args[0]);
        serverSocket = new DatagramSocket(port);

        byte[] receivePacketArray = new byte[MTU];

        DatagramPacket receivePacket, ackPacketToSend;

        while (true)
        {
            System.out.print("Waiting for new connection....\n");

            receivePacket = new DatagramPacket(receivePacketArray, MTU);
            serverSocket.receive(receivePacket);
            //serverSocket.setSoTimeout(500); // Timeout on receiving data packets

            byte[] ackSessionNumber = Arrays.copyOfRange(receivePacketArray, 0, 2); //For checking validation

            byte[] trueDataLengthArray = Arrays.copyOfRange(receivePacketArray, 8, 17);
            long trueDataLength = ByteBuffer.wrap(trueDataLengthArray).getLong(); //True File Length
            int p = (int)(Math.ceil((double)trueDataLength / MTU)); // packet number
            long N = trueDataLength + (p * 3) + 4; // full file size
            int pTrue = (int)(Math.ceil((double)N / MTU)); // real packet number to test
            int ogon = 0;
            ogon = (int)(N - (MTU * (pTrue-1)));

            InetAddress clientHost = receivePacket.getAddress();
            int clientPort = receivePacket.getPort();

            /*Checking the incoming packet. Start-/Data Packet?  */
            byte[] checkStart = Arrays.copyOfRange(receivePacketArray, 3, 8);
            //Byte Array to check 5 bytes on the word Start
            String start = new String(checkStart); //String for the word Start to check it

            if (Objects.equals(start, new String("Start"))) // Working with Start Packet if the word Start equals
            {
                /*Check CRC*/
                byte[] incomeStartPacket = Arrays.copyOfRange(receivePacketArray, 0,
                        receivePacket.getLength() - 4);
                byte[] incomeChecksum = Arrays.copyOfRange(receivePacketArray, receivePacket.getLength() - 4,
                        receivePacket.getLength());

                long toTestCheckSum = byteToLong(incomeChecksum, 0, 4);

                Checksum checksum = new CRC32();
                checksum.update(incomeStartPacket);
                long ChecksumValue = checksum.getValue();

                if (ChecksumValue != toTestCheckSum)  //If CRC does not match then quit
                {
                    System.out.println("CRC Does not match.");
                    break;
                }

                /*Sending ACK 0: Start Packet*/
                System.out.println("Sending ACK...");
                byte[] ackArray = Arrays.copyOfRange(receivePacketArray, 0, 3);

                ackPacketToSend = new DatagramPacket(ackArray, ackArray.length,
                        clientHost, clientPort);
                serverSocket.send(ackPacketToSend);
            }

            /*Waiting for Data Packets*/

            //Reading File Name
            short fileNameLength = ByteBuffer.wrap(receivePacketArray, 16, 2).getShort();
            String fileName = new String(Arrays.copyOfRange(receivePacketArray, 18,
                    fileNameLength+18), "UTF-8");
            File file = new File(fileName);
            int fileNum = 1;

            while(file.exists())
            {
                file = new File(CreatingFileName(fileName, fileNum));
                fileNum++;
            }

            FileOutputStream fos = new FileOutputStream(file, true);
            ByteArrayOutputStream targetStream = new ByteArrayOutputStream();

            receivePacket = new DatagramPacket(receivePacketArray, MTU);

            int n = 1; //Packet Number hidden

            byte previousPacketNumber = 0;

            System.out.println("Waiting for Data Packets...");

            while (n <= pTrue)
            {
                serverSocket.receive(receivePacket);

                byte[] validationPacketNumberArray = Arrays.copyOfRange(receivePacketArray, 2, 3);

                System.out.println("Packet number: " + n +  " Real Number: " + validationPacketNumberArray[0] + ": "
                        + receivePacketArray.length);

                /*On every packet sending ACK back*/
                if(CheckPacketValidation(ackSessionNumber, Arrays.copyOfRange(receivePacketArray, 0, 2),
                        validationPacketNumberArray, previousPacketNumber))
                {
                    previousPacketNumber = validationPacketNumberArray[0];
                    //WriteBytesIntoFile();
                    SendAck(Arrays.copyOfRange(receivePacketArray, 0, 3), clientHost, clientPort);

                    if(n != pTrue)
                    {
                        targetStream.write(receivePacketArray, 3, 1497);
                    }
                    else
                    {
                        targetStream.write(receivePacketArray, 3, ogon-7);
                    }
                    n++;
                }
            }
            /*Testing last packet CRC before completing the file*/
            Checksum checksumFile = new CRC32();
            checksumFile.update(targetStream.toByteArray());
            long checksumCRCToTest = checksumFile.getValue(); //Converted File to CRC

            byte[] incomeChecksum = Arrays.copyOfRange(receivePacketArray, ogon-4, ogon); //Income CRC
            long toTestCheckSum = byteToLong(incomeChecksum, 0, 4);

            if(checksumCRCToTest == toTestCheckSum)
            {
                /*Completing File*/
                try
                {
                    targetStream.writeTo(fos);
                }
                catch (IOException ioe)
                {
                    ioe.printStackTrace();
                }
                finally
                {
                    fos.close();
                }
            }
            else
            {
                throw new Exception("ERROR: the CRC of data does not match.");
            }

            System.out.println();
            System.out.println("File completely downloaded");
            break;
        }
    }

    /*Converting CRC Byte Array in long*/
    private static long byteToLong(byte[] b, int offset, int size)
    {
        long result = 0;
        for (int i = 0; i < size; i++)
        {
            result = (result << 8) | (b[offset + i] & 0xFF);
        }
        return result;
    }

    private static boolean CheckPacketValidation(byte[] ackSession, byte[] ackSessionToTest, byte[] ackPacket, byte previousAckPacket)
    {
        if(Arrays.equals(ackSession, ackSessionToTest) && (ackPacket[0] != previousAckPacket))
        {
            return true;
        }
        return false;
    }

    /*Sending Ack to proceed*/
    private static void SendAck(byte[] ackArray, InetAddress clientHost, int clientPort) throws Exception
    {
        DatagramPacket ackPacketToSend = new DatagramPacket(ackArray, ackArray.length, clientHost, clientPort);
        serverSocket.send(ackPacketToSend);
    }

    /*Adding (number) to File name if File exists*/
    private static String CreatingFileName(String fileName, int fileNum)
    {
        String newFileName = new String();
        int counter = 0;
        int fullSize = fileName.length();

        for (char ch: fileName.toCharArray())
        {
            if(ch == 46)
            {
                break;
            }
            counter++;
        }

        newFileName = fileName.substring(0, counter) + "(" + fileNum + ")" + fileName.substring(counter, fullSize);

        return newFileName;
    }


    /*
     * Print ping data to the standard output stream.
     */
    /*
    public static void writeInFile(DatagramPacket packet) throws Exception
    {
        byte[] packetByte = packet.getData();

        FileOutputStream fileOut = new FileOutputStream("alice1New.txt");
        int packetLength = packetByte.length;

            fileOut.write(packetByte, 0, packetLength);

    }

    private static void printData(DatagramPacket request) throws Exception
    {
        // Obtain references to the packet's array of bytes.
        byte[] buf = request.getData();

        // Wrap the bytes in a byte array input stream,
        // so that you can read the data as a stream of bytes.
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);

        // Wrap the byte array output stream in an input stream reader,
        // so you can read the data as a stream of characters.
        InputStreamReader isr = new InputStreamReader(bais);

        // Wrap the input stream reader in a bufferred reader,
        // so you can read the character data a line at a time.
        // (A line is a sequence of chars terminated by any combination of \r and \n.)
        BufferedReader br = new BufferedReader(isr);

        // The message data is contained in a single line, so read this line.
        String line = br.readLine();

        // Print host address and data received from it.
        /*System.out.println(
                "Received from " +
                        request.getAddress().getHostAddress() +
                        ": " +
                        new String(line) );
        System.out.println("Received text" + new String(line));
    }*/
}