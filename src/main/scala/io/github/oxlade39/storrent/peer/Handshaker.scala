package io.github.oxlade39.storrent.peer

import akka.actor._
import akka.util.{ByteStringBuilder, ByteString}
import akka.event.LoggingReceive
import scala.Some
import akka.io.Tcp

object Handshaker {
  trait HandshakeMessage
  case class HandshakeSuccess(overflow: ByteString = ByteString.empty) extends HandshakeMessage
  case object HandshakeFailed extends HandshakeMessage

  def props(connection: ActorRef, handshake: Handshake) = Props(new Handshaker(connection, handshake))
}

class Handshaker(connection: ActorRef, handshake: Handshake) extends Actor with ActorLogging {
  import Handshaker._
  import Handshake._
  import akka.io.Tcp._
  import context._
  import concurrent.duration._

  connection ! Tcp.Write(handshake.encoded)
  setReceiveTimeout(15.seconds)

  def receive = LoggingReceive(buffering(ByteString.newBuilder))

  def handshakeParser: HandshakeParser = Handshake

  def buffering(buffer: ByteStringBuilder): Receive = {
    case Received(data) =>
      buffer.append(data)
      if (buffer.length >= handshakeSize) {
        val handshakeBytes: ByteString = buffer.result()
        parent ! (handshakeParser.parse(handshakeBytes) match {
          case Some(Handshake(infoHash, peerId)) => HandshakeSuccess(handshakeBytes.drop(Handshake.handshakeSize))
          case None => HandshakeFailed
        })
        stop(self)
      } else {
        become(buffering(buffer))
      }

    case ReceiveTimeout =>
      parent ! HandshakeFailed
      stop(self)
  }
}
