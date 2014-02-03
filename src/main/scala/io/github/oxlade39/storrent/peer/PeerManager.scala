package io.github.oxlade39.storrent.peer

import akka.actor._
import akka.event.LoggingReceive
import akka.actor.Terminated
import io.github.oxlade39.storrent.core.Torrent

object PeerManager {
  import concurrent.duration._

  val MaxConnections = 30
  val checkStatusDuration = 500.millis

  def props(torrent: Torrent, pieceManager: ActorRef) =
    Props(new PeerManager(torrent, pieceManager, Downloader2.props(torrent, pieceManager)))

  private[PeerManager] case object CheckStatus
  case object GetConnectedPeers
  case class PeerConnected(peerConnection: ActorRef)

  /**
   * @param connected mapping of PeerConnection ActorRef to Peer
   */
  case class ConnectedPeers(connected: Map[ActorRef, Peer])
}

class PeerManager(torrent: Torrent,
                  pieceManager: ActorRef,
                  downloaderProps: Props)
  extends Actor with ActorLogging {

  import PeerManager._
  import context._
  import SupervisorStrategy._

  var allPeers = Set.empty[Peer]
  var connectedPeers = Map.empty[ActorRef, Peer]
  def unconnectedPeers = allPeers.filterNot(p => connectedPeers.exists(kv => kv._2 == p))

  val downloader = context.actorOf(downloaderProps, "downloader")

  system.scheduler.schedule(checkStatusDuration, checkStatusDuration, self, CheckStatus)

  override val supervisorStrategy: SupervisorStrategy = {
    def stoppingDecider: Decider = {
      case _: Exception if sender == downloader ⇒ Restart
      case _: Exception ⇒ Stop
    }
    OneForOneStrategy()(stoppingDecider)
  }

  def receive = LoggingReceive {
    case Discovered(peers) =>
      log.info("discovered {} peers", peers.size)
      allPeers ++= peers

    case CheckStatus =>
      if (connectedPeers.size < MaxConnections) {
        val toConnectOption: Option[Peer] = unconnectedPeers.headOption
        toConnectOption.foreach{ toConnect =>
          val pc = watch(actorOf(PeerConnection.props(toConnect, torrent, pieceManager), s"peer-connection-${toConnect.id.id}"))
          connectedPeers += (pc -> toConnect)
        }
      }

    case GetConnectedPeers =>
      sender ! ConnectedPeers(connectedPeers)

    case Terminated(peerConnection) =>
      val peer = connectedPeers.get(peerConnection)
      peer foreach { p =>
        allPeers -= p
        connectedPeers -= peerConnection
      }
  }
}
