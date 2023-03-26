package com.data.acquisition.proxy.platform;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public class Peer {

  private static InetAddress mediatorIP;
  private static int mediatorTcpDiscussionPort;
  private static int mediatorTcpPunchPort;

  private Socket socketDiscussion, socketPunch;

  private final BufferedReader inDiscussion;
  private final BufferedOutputStream outDiscussion;

  private BufferedReader inPunch;
  private BufferedOutputStream outPunch;

  private Thread listenOnHole, writeOnHole;

  /**
   * Constructor of Peer
   *
   * @param ip                the ip addr of the mediator
   * @param tcpDiscussionPort the tcp port to connect to the mediator for discussion
   * @param tcpPunchPort      the tcp port to connect to the mediator for punching holes
   * @throws IOException if something goes wrong
   */
  public Peer(InetAddress ip, int tcpDiscussionPort, int tcpPunchPort) throws IOException {

    //create a socket to connect to the server
    try {
      socketDiscussion = new Socket(ip, tcpDiscussionPort);
      socketPunch = new Socket(ip, tcpPunchPort);
    } catch (IOException ex) {
      System.err.println("Exception creating a socket: " + ex);
    }

    //create input and output stream
    inDiscussion = new BufferedReader(new InputStreamReader(socketDiscussion.getInputStream()));
    outDiscussion = new BufferedOutputStream(socketDiscussion.getOutputStream());

    inPunch = new BufferedReader(new InputStreamReader(socketPunch.getInputStream()));
    outPunch = new BufferedOutputStream(socketPunch.getOutputStream());

    start();
  }

  private void start() {
    System.out.println("Read from server");
    try {
      //Wait for message
      String message = inDiscussion.readLine();
      String[] tokens = message.split("~~");

      String peerPublicIP = tokens[0].trim();
      String peerPublicPort = tokens[1].trim();
      String peerLocalPort = tokens[2].trim();
      System.out.println("****************************************");
      System.out.println("PEER PUBLIC IP seen by server: " + peerPublicIP);
      System.out.println("PEER PUBLIC TCP PORT seen by server: " + peerPublicPort);
      System.out.println("PEER LOCAL  TCP PORT seen by server: " + peerLocalPort);
      System.out.println("****************************************");
      System.out.println("OWN LOCAL PORT: " + socketPunch.getLocalPort());

      //Received all info needed -> proceed hole punching
      proceedHolePunching(
          InetAddress.getByName(peerPublicIP),
          Integer.parseInt(peerLocalPort),
          socketPunch.getLocalPort()
      );
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
  }

  private void listenDataOnHole(String addr, int port) {
    this.listenOnHole = new Thread(() -> {
      boolean endOfStream = false;
      while (!endOfStream) {
        try {
          String message = inPunch.readLine();
          if (message == null) {
            endOfStream = true;
          } else {
            System.out.println("[Received from " + addr + ":" + port + "] " + message.trim());
          }
        } catch (IOException ex) {
          ex.printStackTrace();
          endOfStream = true;
        }
      }
    }, "listen-data-on-hole");
    this.listenOnHole.start();
  }

  private void writeDataOnHole() {
    this.writeOnHole = new Thread(() -> {
      int j = 0;
      String msg;
      while (true) {
        try {
          msg = "MSG #" + j;
          outPunch.write(msg.getBytes());
          outPunch.write('\n');
          outPunch.flush();
          j++;
          Thread.sleep(2000);
        } catch (IOException | InterruptedException e) {
          throw new RuntimeException("Error writing data on hole", e);
        }
      }
    });

    this.writeOnHole.start();
  }

  private void proceedHolePunching(InetAddress addrToConnect, int portToConnect, int localPort)
      throws IOException {
    if (socketPunch != null) {
      outPunch = null;
      inPunch = null;
      String addr = addrToConnect.getHostAddress().trim();

      System.out.println("Attempt to connect to : " + addr + ":" + portToConnect);
      try {
        //Close this socket actually connected to the mediator
        socketPunch.setReuseAddress(true);
        socketPunch.close();

        //Create a new one
        socketPunch = new Socket();
        socketPunch.setReuseAddress(true);

        //Bind it to the same addr
        socketPunch.bind(new InetSocketAddress(localPort));

        //Connect to the distant peer
        socketPunch.connect(new InetSocketAddress(addrToConnect, portToConnect));

        //Init in and out
        inPunch = new BufferedReader(new InputStreamReader(socketPunch.getInputStream()));
        outPunch = new BufferedOutputStream(socketPunch.getOutputStream());

      } catch (ConnectException ce) {
        System.out.println("Punch: Connection refused");
      }

      if (outPunch != null && inPunch != null) {
        System.out.println("Punch: Connected to : " + addr + ":" + portToConnect);
        listenDataOnHole(addr, portToConnect);
        writeDataOnHole();
      } else {
        System.err.println("Error when attempting to connect");
      }
    }
  }

  //Entry point
  public static void main(String[] args) throws IOException {

    if (args.length > 0) {
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
    } else {
      System.out.println("Peer running with default ports 9000 and 9001");

      //by default use localhost
      mediatorIP = InetAddress.getByName("127.0.0.1");
      //default port for tcp
      mediatorTcpDiscussionPort = 9000;
      //default port for udp
      mediatorTcpPunchPort = 9001;

    }
    new Peer(mediatorIP, mediatorTcpDiscussionPort, mediatorTcpPunchPort);
  }
}