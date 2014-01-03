package io.github.oxlade39.storrent.peer

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import akka.io.{IO, Tcp}
import concurrent.duration._
import akka.event.LoggingReceive
import java.net.InetSocketAddress
import io.github.oxlade39.storrent.core.Torrent

/**
 * @author dan
 */
object PeerConnection {
  def props(peer: Peer, torrent: Torrent) = Props(new PeerConnection(peer, torrent))

  val connectionTimeout = 30.seconds
}

class PeerConnection(peer: Peer, torrent: Torrent) extends Actor with ActorLogging {
  import context._
  import PeerConnection._
  import Handshaker._
  import io.github.oxlade39.storrent.Storrent._

  IO(Tcp) ! Tcp.Connect(peer.address, timeout = Some(connectionTimeout))

  def receive = unconnected

  def unconnected: Receive = LoggingReceive {
    case Tcp.Connected(remoteAddress, localAddress) =>
      val connection = sender
      connection ! Tcp.Register(self)
      log.info("connected, beginning handshake")
      val handshake = Handshake(torrent.infoHash, LocalPeer.id)
      become(handshaking(watch(actorOf(Handshaker.props(connection, handshake), "handshaker")), (remoteAddress, localAddress)))

    case _ => stop(self)
  }
  
  def handshaking(handshaker: ActorRef, addresses: (InetSocketAddress, InetSocketAddress)): Receive = LoggingReceive {
    case data: Tcp.Received => 
      handshaker forward data
      
    case HandshakeSuccess =>
      log.info("now connected with handshake")
      become(connected(watch(actorOf(PeerProtocol.props(), "peer-proc")), addresses))
      
    case HandshakeFailed => 
      stop(self)
  }

  def connected(protocol: ActorRef, addresses: (InetSocketAddress, InetSocketAddress)): Receive = LoggingReceive {
    case data: Tcp.Received =>
      protocol forward data

    case _ => stop(self)
  }
}