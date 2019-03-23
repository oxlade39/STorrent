package io.github.oxlade39.storrent.peer

import akka.actor._
import akka.event.LoggingReceive

import concurrent.duration._
import scala.util.Random
import scala.concurrent.Future
import akka.util.Timeout
import io.github.oxlade39.storrent.config.Settings
import io.github.oxlade39.storrent.core.Torrent
import io.github.oxlade39.storrent.peer.PeerProtocol.PeerStatus

object Downloader {
  val DefaultTryDownloadFrequency = 30.seconds
  implicit val timeout = Timeout(5.seconds)
  
  case class FindPieceToDownload(peer: ActorRef, localPeerStatus: PeerStatus, remotePeerStatus: PeerStatus)
  case class StartDownload(peer: ActorRef, pieceIndex: Int)

  case class ActivePeerPieceDownload(piece: Int, peer: ActorRef, pieceDownloader: ActorRef)

  def props(torrent: Torrent,
            pieceManager: ActorRef,
            tryDownloadFrequency: FiniteDuration = Downloader.DefaultTryDownloadFrequency) =
    Props(new Downloader(torrent, pieceManager, tryDownloadFrequency))
}

class Downloader(torrent: Torrent, pieceManager: ActorRef, tryDownloadFrequency: FiniteDuration)
  extends Actor with ActorLogging {

  import Downloader._
  import PeerProtocol._
  import io.github.oxlade39.storrent.piece.PieceManager._
  import context._
  import akka.pattern._

  val settings: Settings = Settings(context.system)
  var activeDownloads = Map.empty[Int, ActivePeerPieceDownload]
  system.scheduler.schedule(tryDownloadFrequency, tryDownloadFrequency, parent, PeerManager.GetConnectedPeers)

  def receive = LoggingReceive {

    case PeerManager.ConnectedPeers(connected) => 
      sendFindPieceToDownloadToSelf(connected)
      
    case FindPieceToDownload(peer, localPeerStatus, remotePeerStatus) =>
      sendStartDownloadToSelf(peer)

    case StartDownload(peer, pieceIndex) =>
      val toDl =
        if (pieceIndex < (torrent.pieceCount - 1))
          DownloadPiece(index = pieceIndex,
            size = torrent.pieceSize,
            offset = torrent.pieceSize * pieceIndex,
            hash = torrent.pieceHashes(pieceIndex))
        else
          DownloadPiece(index = pieceIndex,
            size = (torrent.getSize - (torrent.pieceSize * pieceIndex)).toInt,
            offset = torrent.pieceSize * pieceIndex,
            hash = torrent.pieceHashes(pieceIndex))

      val downloader = watch(actorOf(PieceDownloader.props(peer, toDl, settings.MaxConcurrentRequestsPerPeer), s"piece-$pieceIndex"))
      activeDownloads += (pieceIndex -> ActivePeerPieceDownload(pieceIndex, peer, downloader))

    case complete: PieceDownloader.Success =>
      log.info("completed download of piece {}", complete.downloadPiece)
      unwatch(sender)
      removePieceDownloader(sender)
      pieceManager ! complete

    case Terminated(child) =>
      log.warning("{} terminated before completing download", child)
      removePieceDownloader(child)

  }

  def sendFindPieceToDownloadToSelf(connected: Map[ActorRef, Peer]) {
    val notAlreadyDownloading = connected.filterKeys { peer =>
      !activeDownloads.values.exists(activeDL => activeDL.peer == peer)
    }
    val findPieces = for {
      (peer, peerId) <- Random.shuffle(notAlreadyDownloading)
    } yield {
      log.debug("asking {} for status", peer)
      (peer ? GetPeerStatus).mapTo[(PeerStatus, PeerStatus)].map {
        case (local, remote) =>
          log.info("response from GetPeerStatus {}", (local, remote))
          FindPieceToDownload(peer, local, remote)
      }
    }

    val nextStep = Future.find(findPieces){ startDownload =>
      !startDownload.localPeerStatus.choked && startDownload.localPeerStatus.interested
    }

    for {
      dl <- nextStep
      if dl.isDefined
    } yield self ! dl.get
  }

  def sendStartDownloadToSelf(peer: ActorRef) {
    val futureReply = (pieceManager ? GetPeerPieceMappings).map {
      case mappings: PeerPieceMappings =>
        val starts = for {
          (pieceIndex, peerIdsWhoHave) <- mappings.rarest
          if !activeDownloads.contains(pieceIndex)
        } yield StartDownload(peer, pieceIndex)
        starts.headOption
    }
    for {
      reply <- futureReply
      if reply.isDefined
    } yield self ! reply.get
  }

  def removePieceDownloader(child: ActorRef) {
    activeDownloads = activeDownloads.filterNot {
      case (_, ActivePeerPieceDownload(_, _, pieceDownloader)) => pieceDownloader == child
    }
  }

}

object PieceDownloader {
  case class Success(downloadPiece: DownloadPiece)
  private[PieceDownloader] case class DownloadingPiece(pendingRequestOffsets: List[Int],
                                                       received: DownloadPiece,
                                                       requester: ActorRef)

  def props(peer: ActorRef,
            initialPiece: DownloadPiece,
            maxPendingRequests: Int,
            requestLength: Int = Request.DEFAULT_REQUEST_SIZE) =
    Props(new PieceDownloader(peer, initialPiece, maxPendingRequests, requestLength))
}

class PieceDownloader (peer: ActorRef,
                      initialPiece: DownloadPiece,
                      maxPendingRequests: Int,
                      requestLength: Int)
  extends Actor
  with ActorLogging {

  import PieceDownloader._
  import context._

  watch(peer)
  startDownloading(initialPiece)

  def startDownloading(p: DownloadPiece): Unit = {
    log.info("starting download")
    val pendingRequestOffsets = 0.until(maxPendingRequests).reverseMap { offsetIndex ⇒
      val offset = requestLength * offsetIndex
      request(p.index, offset)
      offset
    }
    val piece = DownloadingPiece(pendingRequestOffsets.toList, p, sender)
    become(downloading(piece))
  }

  def request(pieceIndex: Int, offset: Int, length: Int =  requestLength): Unit = {
    log.debug("sending request for {}:+{} to {}", pieceIndex, offset, peer)
    peer ! PeerConnection.Send(Request(pieceIndex, offset, length))
  }

  def downloading(dl: DownloadingPiece): Receive = LoggingReceive {
    case Piece(index, begin, block) ⇒ {
      log.debug("received offset {} of piece {}", begin, index)
      val updatedDownload = dl.received + Block(begin, block)
      if (updatedDownload.isValid) {
        log.info("finished download piece {} telling {}", index, parent)
        parent ! Success(updatedDownload)
        stop(self)
      } else {
        val lastDownloadOffset = dl.pendingRequestOffsets.head
        val bytesRemaining = initialPiece.size - lastDownloadOffset
        val nextRequestedOffset = lastDownloadOffset + requestLength

        val length = Math.min(requestLength, bytesRemaining)

        if(!updatedDownload.hasEnoughBytesToBeComplete) {
          log.debug("sending next request for offset {}", nextRequestedOffset)
          request(dl.received.index, nextRequestedOffset, length)
          val nextRequests = nextRequestedOffset :: dl.pendingRequestOffsets.filterNot(_ == begin)
          val updated = dl.copy(pendingRequestOffsets = nextRequests, received = updatedDownload)
          become(downloading(updated))
        } else {
          log.warning("reached end of requests but isn't valid")
          stop(self)
        }

      }
    }

    case Terminated(child) => {
      // schedule termination as race condition with receiving pending pieces
      log.info("child has terminated. Scheduling terminating in 30 seconds")
      context.system.scheduler.scheduleOnce(30.seconds, self, PoisonPill)
    }
  }

  def receive = LoggingReceive {
    case _ => ???
  }

}