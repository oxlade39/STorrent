package io.github.oxlade39.storrent.peer

import akka.actor._
import akka.event.LoggingReceive
import akka.actor.Terminated
import io.github.oxlade39.storrent.core.Torrent

object PeerManager {
  import concurrent.duration._

  val MaxConnections = 50
  val checkStatusDuration = 5.seconds

  def props(torrent: Torrent, pieceManager: ActorRef) = Props(new PeerManager(torrent, pieceManager))


  private[PeerManager] case object CheckStatus
}

class PeerManager(torrent: Torrent, pieceManager: ActorRef) extends Actor with ActorLogging {
  import PeerManager._
  import context._

  var allPeers = Set.empty[Peer]
  var connectedPeers = Map.empty[ActorRef, Peer]
  def unconnectedPeers = allPeers.filterNot(p => connectedPeers.exists(kv => kv._2 == p))

  system.scheduler.schedule(checkStatusDuration, checkStatusDuration, self, CheckStatus)

  def receive = LoggingReceive {
    case Discovered(peers) =>
      allPeers ++= peers

    case CheckStatus =>
      if (connectedPeers.size < MaxConnections) {
        val toConnectOption: Option[Peer] = unconnectedPeers.headOption
        toConnectOption.foreach{ toConnect =>
          val pc = watch(actorOf(PeerConnection.props(toConnect, torrent, pieceManager), s"peer-connection-${toConnect.id.id}"))
          connectedPeers += (pc -> toConnect)
        }
      }

    case Terminated(peerConnection) =>
      val peer = connectedPeers.get(peerConnection)
      peer foreach { p =>
        allPeers -= p
        connectedPeers -= peerConnection
      }
  }
}
