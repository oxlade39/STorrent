package io.github.oxlade39.storrent.peer

import akka.actor.{Props, ActorLogging, Actor}

/**
 * @author dan
 */
object PeerConnection {
  def props = Props(new PeerConnection)
}

class PeerConnection extends Actor with ActorLogging {
  def receive = ???
}