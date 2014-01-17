package io.github.oxlade39.storrent.piece

import io.github.oxlade39.storrent.peer.{Bitfield, PeerId}
import io.github.oxlade39.storrent.core.Torrent
import akka.actor.{ActorRef, ActorLogging, Actor, Props}
import akka.event.LoggingReceive

/**
 * @author dan
 */
object PieceManager {
  def props(torrent: Torrent) = Props(new PieceManager(torrent))

  case class PeerHasPieces(peer: PeerId, pieces: Bitfield)
  case object GetPeerPieceMappings

  case class PeerPieceMappings(global: Pieces, pieceCounts: Map[Int, Set[PeerId]] = Map.empty) {
    private[this] def countsWithDefault = pieceCounts.withDefaultValue(Set.empty[PeerId])

    def ++(peerId: PeerId, pieces: Pieces): PeerPieceMappings = {
      val updatedGlobal = global ++ pieces
      val updatedPieceCounts = pieces.has.foldLeft(countsWithDefault) { (counts, piece) =>
        counts.updated(piece, counts(piece) + peerId)
      }
      copy(updatedGlobal, updatedPieceCounts)
    }

    /**
     * @return (pieceIndex, peerIds) sorted in order of rarest pieces first
     */
    def rarest: Seq[(Int, Set[PeerId])] = pieceCounts.toSeq.filterNot(_._2.isEmpty).sortBy(_._2.size)
  }

  case class Pieces(size: Int, has: Set[Int] = Set.empty) {
    def ++(that: Pieces) = {
      assert(that.size == this.size, s"sizes must match: ${that.size} != ${this.size}")
      copy(has = has ++ that.has)
    }

    def toBitfield = {
      val bits = for {
        (_, index) <- Seq.fill(size)(false).zipWithIndex
      } yield has.contains(index)
      Bitfield(bits)
    }
  }
}

class PieceManager(torrent: Torrent) extends Actor with ActorLogging {
  import PieceManager._

  var peerPieceMappings = PeerPieceMappings(
    global = Pieces(torrent.pieceCount)
  )

  var connections = Map.empty[PeerId, ActorRef]

  def receive = LoggingReceive {
    case GetPeerPieceMappings => sender ! peerPieceMappings

    case PeerHasPieces(peer, pieces) =>
      connections += (peer -> sender)

      val setPieces = for {
        (isSet, piece) <- pieces.bitfield.zipWithIndex
        if isSet
      } yield piece
      peerPieceMappings ++= (peer, Pieces(size = pieces.bitfield.size, setPieces.toSet))
  }
}
