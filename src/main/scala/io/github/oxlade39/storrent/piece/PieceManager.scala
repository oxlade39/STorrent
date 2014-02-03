package io.github.oxlade39.storrent.piece

import io.github.oxlade39.storrent.peer._
import io.github.oxlade39.storrent.core.Torrent
import akka.actor._
import akka.event.LoggingReceive
import io.github.oxlade39.storrent.peer.Have
import io.github.oxlade39.storrent.persistence.{Persistence, FolderPersistence}
import java.io.File

/**
 * @author dan
 */
object PieceManager {
  def props(torrent: Torrent)(persistenceProps: Props = FolderPersistence.props(torrent, new File("downloads"))) =
    Props(new PieceManager(torrent, persistenceProps))

  case class PeerHasPieces(peer: PeerId, pieces: Bitfield)
  case object GetPeerPieceMappings

  case class PeerPieceMappings(global: Pieces,
                               localPieces: Pieces,
                               pieceCounts: Map[Int, Set[PeerId]] = Map.empty) {
    private[this] def countsWithDefault = pieceCounts.withDefaultValue(Set.empty[PeerId])

    def ++(peerId: PeerId, pieces: Pieces): PeerPieceMappings = {
      val updatedGlobal = global ++ pieces
      val updatedPieceCounts = pieces.has.foldLeft(countsWithDefault) { (counts, piece) =>
        counts.updated(piece, counts(piece) + peerId)
      }
      copy(global = updatedGlobal, pieceCounts = updatedPieceCounts)
    }
    
    def completed(index: Int) = copy(localPieces = localPieces + index)

    /**
     * @return (pieceIndex, peerIds) sorted in order of rarest pieces first
     */
    def rarest: Seq[(Int, Set[PeerId])] =
      pieceCounts.toSeq.filterNot{
        case (index, peerIds) => peerIds.isEmpty || localPieces.has.contains(index)
      }.sortBy(_._2.size)
  }

  case class Pieces(size: Int, has: Set[Int] = Set.empty) {
    def +(pieceIndex: Int) = {
      assert(pieceIndex <= size, s"pieceIndex must be < $size")
      copy(has = has + pieceIndex)
    }

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

class PieceManager(torrent: Torrent, persistenceProps: Props) extends Actor with ActorLogging {
  import PieceManager._

  val persistence = context.actorOf(persistenceProps)

  var peerPieceMappings = PeerPieceMappings(
    global = Pieces(torrent.pieceCount),
    localPieces = Pieces(torrent.pieceCount)
  )
  
  def myPieces = peerPieceMappings.localPieces

  var connections = Map.empty[PeerId, ActorRef]

  def receive = LoggingReceive {
    case GetPeerPieceMappings => sender ! peerPieceMappings

    case PeerHasPieces(peer, pieces) => {
      connections += (peer -> context.watch(sender))

      val setPieces = for {
        (isSet, piece) <- pieces.bitfield.zipWithIndex
        if isSet
      } yield piece

      val piecesIDoNotHave = setPieces.filterNot(myPieces.has.contains)
      if (!piecesIDoNotHave.isEmpty)
        sender ! PeerConnection.Send(Interested)

      peerPieceMappings ++= (peer, Pieces(size = pieces.bitfield.size, setPieces.toSet))
    }

    case complete: PieceDownloader.Success =>
      val index = complete.downloadPiece.index
      peerPieceMappings = peerPieceMappings.completed(index)
      connections.values foreach (_ ! PeerConnection.Send(Have(index)))
      persistence ! Persistence.Persist(complete.downloadPiece)

    case Terminated(connection) =>
      connections = connections.filterNot {
        case (_, c) => connection == c
      }
  }
}
