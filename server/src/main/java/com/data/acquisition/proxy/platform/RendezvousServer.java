package com.data.acquisition.proxy.platform;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @author user
 */
public class RendezvousServer {

  private int tcpDiscussionPort = 9000;
  private int tcpPunchPort = 9001;

  private final PeerInfo peerA = new PeerInfo().setName("Peer A");
  private final PeerInfo peerB = new PeerInfo().setName("Peer B");

  //Constructor using default tcp discussion/punch ports
  public RendezvousServer() {
    try {
      runServer();
    } catch (IOException ex) {
      Logger.getLogger(RendezvousServer.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  //Constructor specify tcp discussion/punch ports
  public RendezvousServer(int userTcpPort, int userUdpPort) {
    this.tcpDiscussionPort = userTcpPort;
    this.tcpPunchPort = userUdpPort;
    try {
      runServer();
    } catch (IOException ex) {
      Logger.getLogger(RendezvousServer.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length > 0) {
      new RendezvousServer(Integer.parseInt(args[0].trim()), Integer.parseInt(args[1].trim()));
    } else {
      new RendezvousServer();
    }
  }

  //Run server listening clients
  void runServer() throws IOException {
    //Create Server Socket for accepting Client TCP connections
    System.out.println("**********");
    System.out.println("SERVER STARTED");
    System.out.println("---------");
    System.out.println("Discussion Port: " + tcpDiscussionPort);
    System.out.println("Punch Port: " + tcpPunchPort);
    System.out.println("**********");

    runDiscussionServer();
    runPunchServer();
  }

  private void runDiscussionServer() {
    new Thread(() -> {
      try {
        var socketConnect = new ServerSocket(tcpDiscussionPort);
        acceptConnectionAndFillPeerInfo(socketConnect, peerA);
        acceptConnectionAndFillPeerInfo(socketConnect, peerB);
      } catch (IOException ioe) {
        ioe.printStackTrace();
      }
    }, "discussion-server").start();
  }

  private static void acceptConnectionAndFillPeerInfo(ServerSocket socketConnect, PeerInfo peerInfo)
      throws IOException {
    String peerName = peerInfo.getName();
    System.out.println("Waiting for " + peerName);
    Socket peerASocket = socketConnect.accept();
    System.out.println(
        peerName + " connected " + peerASocket.getInetAddress() + " " + peerASocket.getPort());
    //Create input and output streams to read/write messages for Peer A
    peerInfo.setInConnect(new BufferedReader(new InputStreamReader(peerASocket.getInputStream())));
    peerInfo.setOutConnect(new BufferedOutputStream(peerASocket.getOutputStream()));
  }

  private void runPunchServer() {
    new Thread(() -> {
      try {
        var socketPunch = new ServerSocket(tcpPunchPort);

        //Accept first client connection
        acceptPunchAndFillPeerInfo(socketPunch, peerA);

        //Accept second client connection
        acceptPunchAndFillPeerInfo(socketPunch, peerB);

        //Once the two clients have punched
        proceedInfosExchange();
      } catch (IOException ioe) {
        ioe.printStackTrace();
      }
    }, "punch-server").start();
  }

  private static void acceptPunchAndFillPeerInfo(ServerSocket socketPunch, PeerInfo peerInfo)
      throws IOException {
    String peerName = peerInfo.getName();
    System.out.println("Waiting for " + peerName + " punch");
    var peerPunchSocket = socketPunch.accept();
    InetSocketAddress remoteSocketAddress = (InetSocketAddress) peerPunchSocket.getRemoteSocketAddress();
    peerInfo
        .setIp(remoteSocketAddress.getAddress().getHostAddress().trim())
        .setLocalPort(String.valueOf(peerPunchSocket.getPort()))
        .setPublicPort(String.valueOf(peerPunchSocket.getLocalPort()));
    System.out.println("Punch from " + peerName + " " + peerPunchSocket.getInetAddress() + " "
        + peerPunchSocket.getPort());

    System.out.println(
        peerName + " punch " + peerPunchSocket.getInetAddress() + " " + peerPunchSocket.getPort());

    //Create input and output streams to read/write messages for Peer
    peerInfo.setInPunch(
        new BufferedReader(new InputStreamReader(peerPunchSocket.getInputStream())));
    peerInfo.setOutPunch(new BufferedOutputStream(peerPunchSocket.getOutputStream()));
  }

  private void printPeerInfo(PeerInfo peer) {
    System.out.println(
        "******" + peer.getName() + " IP AND PORT DETECTED " + peer.getIp() + ":"
            + peer.getLocalPort() + " -> "
            + peer.getPublicPort() + " *****");
  }

  private void proceedInfosExchange() throws IOException {
    printPeerInfo(peerA);
    printPeerInfo(peerB);

    System.out.println("***** Exchanging public IP and port between the clients *****");
    //SENDING Peer B's public IP & PORT TO Peer A
    String peerBInfo = peerB.serialize();
    send(peerBInfo, peerA);

    //SENDING Peer A's public IP & PORT TO Peer B
    String peerAInfo = peerA.serialize();
    send(peerAInfo, peerB);
  }

  private void send(String msg, PeerInfo peer) throws IOException {
    BufferedOutputStream outConnectB = peer.getOutConnect();
    outConnectB.write(msg.getBytes());
    outConnectB.write('\n');
    outConnectB.flush();
  }
}



@Data
@Accessors(chain = true)
class PeerInfo {

  private String name;
  private String ip;
  private String publicPort;
  private String localPort;

  private BufferedReader inConnect, inPunch;
  private BufferedOutputStream outConnect, outPunch;

  public String serialize() {
    return new StringJoiner("~~").add(ip).add(publicPort).add(localPort).toString();
  }
}