package io.github.oxlade39.storrent.piece

import io.github.oxlade39.storrent.peer.{Bitfield, PeerId}
import io.github.oxlade39.storrent.core.Torrent
import akka.actor.{ActorLogging, Actor, Props}

/**
 * @author dan
 */
object PieceManager {
  def props(torrent: Torrent) = Props(new PieceManager(torrent))

  case class PeerHasPieces(peer: PeerId, pieces: Bitfield)
}

class PieceManager(torrent: Torrent) extends Actor with ActorLogging {
  def receive = ???
}
