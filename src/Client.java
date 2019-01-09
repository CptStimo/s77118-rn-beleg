import java.io.*;
import java.lang.reflect.Array;
import java.lang.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.util.zip.*;

public class Client
{
    private static final int MTU = 1500; // Bytes per Data Packet

    public static void main(String[] args) throws Exception
    {
        // Get command line argument
        if (args.length != 3)
        {
            System.out.println("Required arguments: ip, port, file name");
            return;
        }
        int port = Integer.parseInt(args[1]); // Initializing Port Number by argument 1

        ArrayList<Byte> startPacketList = new ArrayList<Byte>(); // Array List for Start Packet

        /*Initializing Datagram Socket and Inet Address by argument 0*/
        DatagramSocket clientSocket = new DatagramSocket();
        //clientSocket.setSoTimeout(100);

        InetAddress ia = InetAddress.getByName(args[0]);

        /*Initializing the File and reading Bytes from it*/
        File file = new File(args[2]);
        byte[] fileContent = Files.readAllBytes(file.toPath());
        ByteArrayInputStream targetStream = new ByteArrayInputStream(fileContent);

        /* Session Number*/
        Random randGen = new Random(); //Random for 16 Bit Session Number
        byte[] sessionNumber = new byte[2]; // 16 Bit Session Number; CHECK
        randGen.nextBytes(sessionNumber); // Generated Session Number
        startPacketList.addAll(WriteByteArraysIntoList(sessionNumber));

        /*Packet Number*/
        byte[] packetNumber = {0}; // CHECK
        startPacketList.addAll(WriteByteArraysIntoList(packetNumber));

        /*Start as ASCII for Start packet*/
        String start = "Start";
        byte[] startToAscii = start.getBytes("US-ASCII"); // CHECK
        startPacketList.addAll(WriteByteArraysIntoList(startToAscii));

        /*64 Bit File Length*/
        long fileLength; // 64 Bit
        fileLength = (long) fileContent.length; //Length of the to send file; CHECK
        byte[] fileLengthToArray = ByteBuffer.allocate(8).putLong(fileLength).array();
        startPacketList.addAll(WriteByteArraysIntoList(fileLengthToArray));

        /*2 Byte File Name Length*/
        String fileName = file.getName();
        short nameLength = (short) fileName.length();
        byte[] fileNameLength = ByteBuffer.allocate(2).putShort(nameLength).array(); // CHECK
        startPacketList.addAll(WriteByteArraysIntoList(fileNameLength));

        /*0-255 Byte File Name in UTF-8*/
        byte[] nameUTF = fileName.getBytes("UTF-8"); // CHECK
        startPacketList.addAll(WriteByteArraysIntoList(nameUTF));

        /*CRC-32 for Start Packet and preparing Start Packet*/
        Checksum checksum = new CRC32();
        checksum.update(ConvertArrayListToByteArray(startPacketList, startPacketList.size()));
        long ChecksumValue = checksum.getValue(); //CRC Checksum ready and now add it to the finalStartPacket
        //byte[] ChecksumValueToByte = ByteBuffer.allocate(8).putLong(ChecksumValue).array(); // Converting CRC to array
        byte[] ChecksumValueToByte = longToByte(ChecksumValue);
        startPacketList.addAll(WriteByteArraysIntoList(ChecksumValueToByte));

        /*CRC-32 for File*/
        Checksum checksumFile = new CRC32();
        checksumFile.update(fileContent);
        long ChecksumFileValue = checksumFile.getValue();
        System.out.println("CRC: " + ChecksumFileValue);
        byte[] ChecksumFileValueToByte = longToByte(ChecksumFileValue);

        /*Converting Start Packet List to byte[] startPacketToSend*/
        byte[] startPacketToSend = ConvertArrayListToByteArray(startPacketList, startPacketList.size());

        /*Start Packet is ready to be sent*/
        DatagramPacket packetToSend = new DatagramPacket(startPacketToSend, startPacketToSend.length, ia, port);
        clientSocket.send(packetToSend);
        Thread.sleep(100);

        /*Getting Confirm Packet (ACK) about sent Start Packet before going further*/
        System.out.println("Waiting ACK...");

        byte[] confirmStartPacketArray = new byte[3];
        DatagramPacket confirmedStartPacket = new DatagramPacket(confirmStartPacketArray, 3);
        clientSocket.receive(confirmedStartPacket);

        byte[] sessionAckArray = Arrays.copyOfRange(confirmStartPacketArray, 0, 2); //ACK for Session Number
        byte[] PacketAckArray = Arrays.copyOfRange(confirmStartPacketArray, 2, 3); //ACK for Port Number

        if (Arrays.equals(PacketAckArray, packetNumber) && Arrays.equals(sessionAckArray, sessionNumber))
        {
            System.out.println("Got ACK");

            /*Sending Data Packets*/
            ArrayList<Byte> dataPacketList = new ArrayList<Byte>(); //Array List for Data

            byte[] dataPacketArray = new byte[MTU]; // Byte Array to store Data Array List
            byte[] dataOnlyArray = new byte[MTU - 3]; // Byte Array for Data

            int p = (int)(Math.ceil((double)fileLength / MTU)); // packet number
            long N = fileLength + (p * 3) + 4; // full file size
            int pTrue = (int)(Math.ceil((double)N / MTU)); // real packet number to test

            int ogon = 0;
            ogon = (int)(N - (MTU * (pTrue-1))); //Last Packet length
            byte[] lastDataPacketArray = new byte[ogon-7];

            int n = 1; // Packet Number hidden

            byte packetNumberOnData = 1; // Real Packet Number 0 or 1 throw mod
            int retryCount = 0; // Tries to resend Data till retryCount is 10

            System.out.println("File:" + fileContent.length);

            while (n <= pTrue)
            {
                dataPacketList.clear();
                dataPacketList = PrepareDataPacket(sessionNumber, packetNumberOnData);

                if(n != pTrue)
                {
                    targetStream.read(dataOnlyArray);
                    dataPacketList.addAll(WriteByteArraysIntoList(dataOnlyArray));
                }
                else
                {
                    targetStream.read(lastDataPacketArray);
                    dataPacketList.addAll(WriteByteArraysIntoList(lastDataPacketArray));
                    dataPacketList.addAll(WriteByteArraysIntoList(ChecksumFileValueToByte));
                }

                dataPacketArray = ConvertArrayListToByteArray(dataPacketList, MTU);

                DatagramPacket dataPacket = new DatagramPacket(dataPacketArray, MTU, ia, port);

                long timeFirst = System.nanoTime();
                clientSocket.send(dataPacket);

                while(true)
                {
                    try
                    {
                        clientSocket.receive(confirmedStartPacket);

                        retryCount = 0;
                    }
                    catch (SocketTimeoutException e)
                    {
                        //resend
                        retryCount++;
                        if( retryCount == 10)
                        {
                            throw new Exception("ERROR: retry amount of sending data packet ran out of 10.");
                        }
                        timeFirst = System.nanoTime();
                        clientSocket.send(dataPacket);
                        continue;
                    }

                    sessionAckArray = Arrays.copyOfRange(confirmStartPacketArray, 0, 2); //ACK for Session Number
                    PacketAckArray = Arrays.copyOfRange(confirmStartPacketArray, 2, 3); //ACK for Port Number
                    if(Arrays.equals(sessionAckArray, sessionNumber) && (PacketAckArray[0] == packetNumberOnData))
                    {
                        double delta = ((double)System.nanoTime()-timeFirst)/1000000;
                        double rate =(((double)MTU / delta));
                        double finalRate = Math.round(rate * 100.0)/100.0;
                        //Thread.sleep(50);

                        System.out.println("Packet number" + n + " Real Number: " + packetNumberOnData + ": "
                                + dataPacket.getLength());
                        System.out.println(finalRate + "KB/s");
                        n++;
                        packetNumberOnData = (byte)(n % 2);
                        break;
                    }
                    else
                    {
                        timeFirst = System.currentTimeMillis();
                        clientSocket.send(dataPacket);
                    }
                }
            }
        }
        else
        {
            System.out.println("ERROR: SasaiKudasai. ACK not approved.");
        }
    }

    /*Converting long to Byte Array*/
    public static byte[] longToByte(long value) //1111 1100 0011 0001 .64561
    {
        byte [] data = new byte[4];
        data[3] = (byte) value;
        data[2] = (byte) (value >>> 8);
        data[1] = (byte) (value >>> 16);
        data[0] = (byte) (value >>> 24);

        return data;
    }

    /*Writing Byte Array into List*/
    public static ArrayList<Byte> WriteByteArraysIntoList(byte[] array)
    {
        ArrayList<Byte> list = new ArrayList<Byte>();

        for(int i = 0; i < array.length; i++)
        {
            list.add(array[i]);
        }
        return list;
    }

    /*Converting List into Byte Array*/
    public static byte[] ConvertArrayListToByteArray(ArrayList<Byte> list, int size)
    {
        byte[] array = new byte[size];
        int j = 0;

        for(Byte b: list)
        {
            array[j++] = b.byteValue();
        }

        return array;
    }

    /*Prepare first 3 bytes of Data List for Data Packet*/
    public static ArrayList<Byte> PrepareDataPacket(byte[] sessionNumber, byte i)
    {
        ArrayList<Byte> list = new ArrayList<Byte>();
        list.addAll(WriteByteArraysIntoList(sessionNumber)); //Adding Session Number to List

        byte[] dataPacketNumber = ByteBuffer.allocate(1).put(i).array();
        list.addAll(WriteByteArraysIntoList(dataPacketNumber));

        return list;
    }
}


