package com.data.acquisition.proxy.platform;/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * @author user
 */
public class PeerB {

  private static InetAddress mediatorIP;
  private static int mediatorTcpDiscussionPort;
  private static int mediatorTcpPunchPort;

  private Socket socketDiscussion, socketClientPunch;
  private ServerSocket socketServerPunch;

  private final BufferedReader inDiscussion;
  private final BufferedOutputStream outDiscussion;

  private BufferedReader inPunch;
  private BufferedOutputStream outPunch;

  private String message = "";
  private String[] tokens = null;
  private boolean respRead;
  private volatile boolean runningHole;

  private Thread readOnHole, listenOnHole, writeOnHole;

  /**
   * Constructor of Peer
   *
   * @param ip                the ip addr of the mediator
   * @param tcpDiscussionPort the tcp port to connect to the mediator for discussion
   * @param tcpPunchPort      the tcp port to connect to the mediator for punching holes
   * @throws IOException if something goes wrong
   */
  public PeerB(InetAddress ip, int tcpDiscussionPort, int tcpPunchPort) throws IOException {

    //create a socket to connect to the server
    try {
      socketDiscussion = new Socket(ip, tcpDiscussionPort);
      socketClientPunch = new Socket(ip, tcpPunchPort);
    } catch (IOException ex) {
      System.err.println("Exception creating a socket: " + ex);
    }

    this.runningHole = true;

    //create input and output stream
    inDiscussion = new BufferedReader(new InputStreamReader(socketDiscussion.getInputStream()));
    outDiscussion = new BufferedOutputStream(socketDiscussion.getOutputStream());

    inPunch = new BufferedReader(new InputStreamReader(socketClientPunch.getInputStream()));
    outPunch = new BufferedOutputStream(socketClientPunch.getOutputStream());

    System.out.println("Read on hole");
    readOnHole();

    System.out.println("sending initial tcp punch message");

    //Send message, the server get all information about the message send (local port, distant port and ip)
    byte[] sendData = "two".getBytes();
    outPunch.write(sendData);
    outPunch.write('\n');
    outPunch.flush();


  }

  private void readOnHole() throws IOException {
    this.readOnHole = new Thread(new Runnable() {
      @Override
      public void run() {
        //create a loop to read the TCP response from the server
        while (!respRead) {
          try {
            //Wait for message
            message = inDiscussion.readLine();

            tokens = message.split("~~");  //split response into tokens for IP and Port

            System.out.println("****************************************");
            System.out.println("My PUBLIC IP seen by server: " + tokens[0]);
            System.out.println("My PUBLIC TCP PORT seen by server: " + tokens[1]);
            System.out.println("My LOCAL  TCP PORT seen by server: " + tokens[2]);
            System.out.println("****************************************\n");

            String clientAPublicIP = tokens[3];
            String clientAPublicTcpPort = tokens[4];
            String clientALocalTcpPort = tokens[5];
            System.out.println("****************************************");
            System.out.println("CLIENT A PUBLIC IP seen by server: " + clientAPublicIP);
            System.out.println("CLIENT A PUBLIC TCP PORT seen by server: " + clientAPublicTcpPort);
            System.out.println("CLIENT A LOCAL  TCP PORT seen by server: " + clientALocalTcpPort);
            System.out.println("OWN LOCAL PORT connected to server: " + socketClientPunch.getLocalPort());
            System.out.println("****************************************");

            respRead = true;

            //ACK SERVER
            outDiscussion.write("ackTwo".getBytes());
            outDiscussion.write('\n');
            outDiscussion.flush();

            //Received all infos needed -> proceed hole punching
            proceedHolePunching(
                InetAddress.getByName(clientAPublicIP.trim()),
                Integer.parseInt(clientALocalTcpPort.trim()),
                socketClientPunch.getLocalPort()
            );
          } catch (IOException ioe) {
            ioe.printStackTrace();
          }
        }
      }
    });

    this.readOnHole.start();
  }

  private void listenConnectionHole(int localPort) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          System.out.println("Listen hole on port: " + localPort);
          socketServerPunch = new ServerSocket(localPort);
          socketClientPunch = socketServerPunch.accept();
          System.out.println("Accepted socket connection on port: " + localPort);
          inPunch = new BufferedReader(new InputStreamReader(socketClientPunch.getInputStream()));
          outPunch = new BufferedOutputStream(socketClientPunch.getOutputStream());
        } catch (Exception e) {
          inPunch = null;
          outPunch = null;
        }
      }
    }, "listen-connection-hole").start();
  }

  private void listenDataOnHole(String addr, int port) {
    this.listenOnHole = new Thread(new Runnable() {
      @Override
      public void run() {
        while (runningHole) {
          try {
            message = inPunch.readLine();
            System.out.println(
                "Received: " + message.trim() + ", From: IP " + addr + " Port " + port);
          } catch (IOException ex) {
            System.err.println("Error " + ex);
          }
        }
      }
    }, "listen-data-on-hole");
    this.listenOnHole.start();
  }

  private void redirectPorts(int from, int to) {
    try {
      Process proc1 = Runtime.getRuntime().exec(
          "iptables -t nat -A PREROUTING -i eth0 -p tcp --dport " + from + " -j REDIRECT --to-port "
              + to);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void writeDataOnHole() {
    this.writeOnHole = new Thread(new Runnable() {
      @Override
      public void run() {
        int j = 0;
        String msg;
        //create Loop to send udp packets
        while (runningHole) {
          try {
            msg = "I AM CLIENT B " + j;
            outPunch.write(msg.getBytes());
            outPunch.write('\n');
            outPunch.flush();
            j++;
            Thread.sleep(2000);
          } catch (IOException e) {
            System.err.println("IOException");
          } catch (Exception e) {
            System.err.println("SleepException");
          }
        }
      }
    });

    this.writeOnHole.start();
  }

  private void proceedHolePunching(InetAddress addrToConnect, int portToConnect, int localPort)
      throws IOException {
    if (this.socketClientPunch != null) {
      outPunch = null;
      inPunch = null;
      String addr = addrToConnect.getHostAddress().trim();

      System.out.println("Start listen on port : " + localPort);
      listenConnectionHole(localPort);

      System.out.println("Attempt to connect to : " + addr + ":" + portToConnect);
      try {
        //Close this socket actually connected to the mediator
        socketClientPunch.setReuseAddress(true);
        socketClientPunch.close();

        //Create a new one
        socketClientPunch = new Socket();
        socketClientPunch.setReuseAddress(true);

        //Bind it to the same addr
        socketClientPunch.bind(new InetSocketAddress(localPort));

        //Connect to the distant client
        socketClientPunch.connect(new InetSocketAddress(addrToConnect, portToConnect));

        //Init in and out
        inPunch = new BufferedReader(new InputStreamReader(socketClientPunch.getInputStream()));
        outPunch = new BufferedOutputStream(socketClientPunch.getOutputStream());


      } catch (ConnectException ce) {
        System.out.println("Punch: Connection refused");
      }

      if (outPunch != null && inPunch != null) {
        System.out.println("Punch: Connected to : " + addr + ":" + portToConnect);
        redirectPorts(localPort, 8080);

        listenDataOnHole(addr, portToConnect);
        writeDataOnHole();
      } else {
        System.err.println("Error when attempting to connect");
      }
    }
  }

  //Entry point
  public static void main(String[] args) throws IOException {

    if (args.length > 0) {//Give args
      try {
        //Get first param server mediator ip address
        mediatorIP = InetAddress.getByName(args[0].trim());
        //Get second params udp port
        mediatorTcpDiscussionPort = Integer.parseInt(args[1].trim());
        //Get third params tcp port
        mediatorTcpPunchPort = Integer.parseInt(args[2].trim());
      } catch (Exception ex) {
        System.err.println("Error in input");
        System.out.println(
            "USAGE: java Peer mediatorIP mediatorTcpDiscussionPort mediatorTcpPunchPort");
        System.out.println("Example: java Peer 127.0.0.1 9000 9001");
        System.exit(0);
      }
    } else {//Give no args
      System.out.println("Peer running with default ports 9000 and 9001");

      //by default use localhost
      mediatorIP = InetAddress.getByName("127.0.0.1");
      //default port for tcp
      mediatorTcpDiscussionPort = 9000;
      //default port for udp
      mediatorTcpPunchPort = 9001;

    }
    new PeerB(mediatorIP, mediatorTcpDiscussionPort, mediatorTcpPunchPort);
  }
}