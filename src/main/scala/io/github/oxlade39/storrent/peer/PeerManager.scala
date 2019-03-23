package io.github.oxlade39.storrent.peer

import akka.actor.{Terminated, _}
import akka.event.LoggingReceive
import io.github.oxlade39.storrent.config.Settings
import io.github.oxlade39.storrent.core.Torrent

object PeerManager {

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
  import SupervisorStrategy._
  import context._

  val settings: Settings = Settings(system)

  var allPeers = Set.empty[Peer]
  var peerBlackList = Set.empty[Peer]
  var connectedPeers = Map.empty[ActorRef, Peer]
  val downloader: ActorRef = context.actorOf(downloaderProps, "downloader")

  def unconnectedPeers: Set[Peer] = allPeers.filterNot(p => connectedPeers.exists(kv => kv._2 == p))

  system.scheduler.schedule(settings.PeerCheckStatusDuration, settings.PeerCheckStatusDuration, self, CheckStatus)

  override val supervisorStrategy: SupervisorStrategy = {
    def stoppingDecider: Decider = {
      case _: Exception if sender == downloader ⇒ Restart
      case _: Exception ⇒ Stop
    }
    OneForOneStrategy()(stoppingDecider)
  }

  def receive = LoggingReceive {
    case Discovered(peers) =>
      val notBlacklisted = peers.filterNot(peerBlackList.contains)
      allPeers ++= notBlacklisted
      log.info("discovered {} peers, now we know {} total peers", notBlacklisted.size, allPeers.size)

    case CheckStatus =>
      if (connectedPeers.size < settings.MaxPeerConnections) {
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
        connectedPeers.get(peerConnection) foreach (p => peerBlackList += p)
        connectedPeers -= peerConnection
      }
  }
}
