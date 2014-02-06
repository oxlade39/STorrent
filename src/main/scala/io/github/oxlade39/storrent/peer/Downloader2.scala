package io.github.oxlade39.storrent.peer

import akka.actor._
import scala.concurrent.{TimeoutException, Future}
import io.github.oxlade39.storrent.core.Torrent
import io.github.oxlade39.storrent.piece.PieceManager
import akka.util.Timeout
import akka.event.LoggingReceive
import concurrent.duration._


object DownloadWorkPullingPattern {
  case class DownloadTask(peerRef: ActorRef, pieceIndex: Int)
  type Work = (Int, Set[DownloadTask])

  case class CurrentWork(work: Seq[Work])
  case object RequestWork
  case object NoWorkAvailable
  case class TaskDone(task: DownloadTask)
  case class TaskFailed(task: DownloadTask)
}

object Downloader2 {
  val MaxDownloads = 30

  case class ConnectedPeerStatus(peerRef: ActorRef,
    peerId: PeerId,
    localStatus: PeerProtocol.PeerStatus,
    remoteStatus: PeerProtocol.PeerStatus)

  def props(torrent: Torrent, pieceManager: ActorRef): Props =
    Props(new Downloader2(torrent, pieceManager))
}

class Downloader2(torrent: Torrent, pieceManager: ActorRef)
  extends Actor with ActorLogging {

  import Downloader2._

  val workQueue = context.actorOf(Props[WorkQueue], "workQueue")
  val workFetcher = context.actorOf(Props(new WorkFetcher(context.parent, pieceManager, workQueue)), "workFetcher")

  val workers = for {
    index <- 0.until(MaxDownloads)
  } yield context.actorOf(Props(new DownloadWorker(workQueue, pieceManager, torrent, 10.seconds)), s"worker$index")


  def receive = LoggingReceive {
    case "nothing" =>
  }
}

class WorkQueue extends Actor with ActorLogging {
  import DownloadWorkPullingPattern._

  var currentWork = Seq.empty[Work]
  var activeTasks = Set.empty[DownloadTask]

  def receive = LoggingReceive {
    case CurrentWork(work) =>
      currentWork = work

    case TaskDone(complete) =>
      activeTasks = activeTasks filterNot(_ == complete)
      currentWork = currentWork filterNot {
        case (pieceIndex, tasks) => tasks.contains(complete)
      }

    case TaskFailed(task) =>
      log.error("{} worker failed", sender)
      activeTasks = activeTasks.filterNot(_ == task)
    
    case RequestWork =>
      val activeActors = activeTasks.map(_.peerRef)
      val activePieces = activeTasks.map(_.pieceIndex)
      val selectedWork = currentWork.foldLeft(Option.empty[DownloadTask]) {
        case (found, work) => 
          if (found.isDefined) found
          else if (activePieces.contains(work._1)) None
          else {
            val found = work._2.find(task => !activeActors.contains(task.peerRef))
            found
          }
      }
      selectedWork foreach (work => activeTasks += work)
      sender ! selectedWork.getOrElse(NoWorkAvailable)
  }
}

class WorkFetcher(peerManager: ActorRef,
                  pieceManager: ActorRef,
                  workQueue: ActorRef,
                  fetchFrequency: FiniteDuration = 10.seconds)
  extends Actor with ActorLogging {

  import Downloader2._
  import scala.util.{Success => TrySuccess, Failure => TryFailure}
  import DownloadWorkPullingPattern._
  import akka.pattern.ask
  import context.dispatcher
  implicit val timeout = Timeout(10.seconds)

  private case object FindWork

  context.system.scheduler.schedule(10.millis, fetchFrequency, self, FindWork)

  def receive = LoggingReceive {
    case FindWork => findWork.recover{
      case t: TimeoutException => Nil
      case p: NoSuchElementException => Nil
    } onComplete {
      case TrySuccess(work) => workQueue ! CurrentWork(work)
      case TryFailure(t) => log.error(t, t.getMessage)
    }
  }

  def askPeerStatus(peerRef: ActorRef): Future[(PeerProtocol.PeerStatus, PeerProtocol.PeerStatus)] = {
    implicit val timeout = Timeout(3.seconds)
    (peerRef ? PeerProtocol.GetPeerStatus).mapTo[(PeerProtocol.PeerStatus, PeerProtocol.PeerStatus)] recover {
      case t: TimeoutException =>
        (PeerProtocol.PeerStatus(choked = true, interested = false),
          PeerProtocol.PeerStatus(choked = true, interested = false))
    }
  }

  def findWork: Future[Seq[(Int, Set[DownloadTask])]] = for {
    connectedPeers <- (peerManager ? PeerManager.GetConnectedPeers).mapTo[PeerManager.ConnectedPeers]
    pieces <- (pieceManager ? PieceManager.GetPeerPieceMappings).mapTo[PieceManager.PeerPieceMappings]
    readyToDownload <- Future.traverse(connectedPeers.connected){ case (peerRef, peer) =>
      askPeerStatus(peerRef)
        .map(st => ConnectedPeerStatus(peerRef, peer.id, st._1, st._2))
        .map{st =>
          val canDownload = !st.localStatus.choked && st.localStatus.interested
          if (canDownload) st
          else st.copy(peerId = PeerId()) // acts as a filter
        }
      }
    potentialPeersToDowloadFrom <- Future.traverse(pieces.rarest) {
      case (pieceIndex, peerIds) => Future {
        val resolvedPeerRefs = peerIds.flatMap(peerId => readyToDownload.find(_.peerId == peerId))
        (pieceIndex, resolvedPeerRefs)
      }
    }
  } yield {
    potentialPeersToDowloadFrom.map {
      case (pieceIndex, potentials) =>
        (pieceIndex, potentials.map(potential =>
          DownloadTask(potential.peerRef, pieceIndex)))
    }
  }
}

class DownloadWorker(workQueue: ActorRef,
                     pieceManager: ActorRef,
                     torrent: Torrent,
                     pollFrequency: FiniteDuration)
  extends Actor with ActorLogging {
  
  import DownloadWorkPullingPattern._

  override def preStart() = {
    workQueue ! RequestWork
  }

  context.setReceiveTimeout(pollFrequency)

  def receive = waitingForWork


  def waitingForWork: Receive = {
    case NoWorkAvailable =>
      log.debug("no work currently available")

    case ReceiveTimeout =>
      workQueue ! RequestWork

    case task: DownloadTask =>
      log.info("beginning work on {}", task)
      val downloader = context.watch(context.actorOf(PieceDownloader.props(
        peer = task.peerRef,
        initialPiece = downloadPiece(task.pieceIndex))))
      context.become(working(task, downloader))
  }

  def working(task: DownloadTask, downloader: ActorRef, timeouts: Int = 0): Receive = {
    case Terminated(child) =>
      workQueue ! TaskFailed(task)
      log.info("child stopped so failing task {} and become waiting", task)
      context.become(waitingForWork)

    case ReceiveTimeout =>
      if ((timeouts + 1) > 10) {
        log.warning("killing downloader {} for {}", downloader, task)
        context.stop(downloader)
      } else {
        log.debug("we're already busy working, increasing timeout count to {}", timeouts + 1)
        context.become(working(task, downloader, timeouts + 1))
      }

    case PieceDownloader.Success(piece) =>
      log.info("notified of success of piece {}", piece)
      context.unwatch(downloader)
      workQueue ! TaskDone(task)
      workQueue ! RequestWork
      pieceManager ! PieceDownloader.Success(piece)
      context.become(waitingForWork)
  }

  def pieceDownloader(task: DownloadTask): Props = PieceDownloader.props(
    peer = task.peerRef,
    initialPiece = downloadPiece(task.pieceIndex)
  )

  def downloadPiece(pieceIndex: Int) = {
    val size =
      if (pieceIndex < (torrent.pieceCount - 1)) torrent.pieceSize
      else (torrent.getSize - (torrent.pieceSize * pieceIndex)).toInt

    DownloadPiece(index = pieceIndex,
      size = size,
      offset = torrent.pieceSize * pieceIndex,
      hash = torrent.pieceHashes(pieceIndex))
  }

}