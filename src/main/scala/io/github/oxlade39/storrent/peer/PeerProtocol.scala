package io.github.oxlade39.storrent.peer

import akka.actor.{Props, ActorLogging, Actor}

/**
 * @author dan
 */
object PeerProtocol {
  def props() = Props(new PeerProtocol)
}

class PeerProtocol extends Actor with ActorLogging {
  def receive = ???
}