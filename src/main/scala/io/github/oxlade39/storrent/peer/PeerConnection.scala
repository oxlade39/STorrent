package io.github.oxlade39.storrent.peer

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import akka.io.{IO, Tcp}
import concurrent.duration._
import akka.event.LoggingReceive
import java.net.InetSocketAddress
import io.github.oxlade39.storrent.core.Torrent
import akka.util.ByteString

/**
 * @author dan
 */
object PeerConnection {
  def props(peer: Peer, torrent: Torrent, pieceManager: ActorRef) =
    Props(new PeerConnection(peer, torrent, pieceManager))

  case class EstablishedConnection(actorRef: ActorRef,
                                   localAddress: InetSocketAddress,
                                   remoteAddress: InetSocketAddress)

  val connectionTimeout = 30.seconds

  case class Send(message: Message)
  case class Received(message: Message)
}

class PeerConnection(peer: Peer, torrent: Torrent, pieceManager: ActorRef) extends Actor with ActorLogging {
  import context._
  import PeerConnection._
  import Handshaker._
  import io.github.oxlade39.storrent.Storrent._
  import io.github.oxlade39.storrent.piece.PieceManager

  IO(Tcp) ! Tcp.Connect(peer.address, timeout = Some(connectionTimeout))

  def receive = unconnected

  def unconnected: Receive = LoggingReceive {
    case Tcp.Connected(remoteAddress, localAddress) =>
      val connection = sender
      connection ! Tcp.Register(self)
      log.info("connected, beginning handshake")
      val handshake = Handshake(torrent.infoHash, LocalPeer.id)
      become(
        handshaking(
          handshaker = watch(actorOf(Handshaker.props(connection, handshake), "handshaker")),
          connection = EstablishedConnection(connection, localAddress, remoteAddress)))

    case _ => stop(self)
  }
  
  def handshaking(handshaker: ActorRef, connection: EstablishedConnection): Receive = LoggingReceive {
    case data: Tcp.Received => 
      handshaker forward data
      
    case HandshakeSuccess =>
      log.info("now connected with handshake")
      unwatch(handshaker)
      become(connected(
        protocolProcessor = peerProtocolProcessor(connection),
        peerProtocol = peerProtocol(),
        connection = connection)
      )
      
    case HandshakeFailed => 
      stop(self)
  }


  def peerProtocol() =
    watch(actorOf(PeerProtocol.props, "peer-protocol"))

  def connected(protocolProcessor: ActorRef,
                peerProtocol: ActorRef,
                connection: EstablishedConnection): Receive = LoggingReceive {
  
    case Tcp.Received(data) =>
      protocolProcessor forward data

    case Send(message) =>
      protocolProcessor forward message
      peerProtocol forward Send(message)

    case Received(message) =>
      peerProtocol forward Received(message)

    case status: PeerProtocol.PeerStatus =>
      status.pieces map (p => PieceManager.PeerHasPieces(peer.id, p.trimmed(torrent))) foreach (pieceManager ! _)

    case PeerProtocol.GetPeerStatus =>
      peerProtocol forward PeerProtocol.GetPeerStatus

    case other =>
      log.warning("stopping as received {}", other)
      stop(self)
  }

  def peerProtocolProcessor(connection: PeerConnection.EstablishedConnection): ActorRef =
    watch(
      actorOf(PeerProtocolProcessor.props(
        bytesProcessor = actorOf(ConnectionWriter.props(connection.actorRef), "connection-writer"),
        messageProcessor = actorOf(MessageReceiver.props, "message-processor")),
        "message-adapter"))

}

object ConnectionWriter {
  def props(connection: ActorRef) = Props(new ConnectionWriter(connection))
}

class ConnectionWriter(connection: ActorRef) extends Actor {
  def receive = {
    case b: ByteString => connection ! Tcp.Write(b)
  }
}

object MessageReceiver {
  def props = Props(new MessageReceiver)
}

class MessageReceiver extends Actor with ActorLogging {
  def receive: Receive = LoggingReceive {
    case m: Message => context.parent ! PeerConnection.Received(m)
  }
}