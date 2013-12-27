package io.github.oxlade39.storrent.peer

import akka.actor.{ReceiveTimeout, Props, Actor, ActorLogging}
import akka.util.{ByteStringBuilder, ByteString}
import akka.event.LoggingReceive

object Handshaker {
  trait HandshakeMessage
  case object HandshakeSuccess extends HandshakeMessage
  case object HandshakeFailed extends HandshakeMessage

  def props() = Props(new Handshaker)
}

class Handshaker extends Actor with ActorLogging {
  import Handshaker._
  import Handshake._
  import akka.io.Tcp._
  import context._
  import concurrent.duration._

  setReceiveTimeout(15.seconds)

  def receive = LoggingReceive(buffering(ByteString.newBuilder))

  def handshakeParser: HandshakeParser = Handshake

  def buffering(buffer: ByteStringBuilder): Receive = {
    case Received(data) =>
      buffer.append(data)
      if (buffer.length >= handshakeSize) {
        parent ! (handshakeParser.parse(buffer.result()) match {
          case Some(Handshake(infoHash, peerId)) => HandshakeSuccess
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
