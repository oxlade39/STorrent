package io.github.oxlade39.storrent

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import java.io.File
import io.github.oxlade39.storrent.core.Torrent
import java.net.{InetAddress, InetSocketAddress}
import akka.event.LoggingReceive
import io.github.oxlade39.storrent.peer.PeerManager

object Storrent {
  case class DownloadTorrent(file: File)
  case class StopDownloading(file: File)
  case class PauseDownloading(file: File)

  val localPort = 0

  object LocalPeer extends peer.Peer(
    new InetSocketAddress(InetAddress.getLocalHost, localPort),
    peer.PeerId()
  )

}

class Storrent extends Actor with ActorLogging {
  import Storrent._
  import context._

  var activeDownloads = Map.empty[File, ActorRef]

  def receive = {
    case DownloadTorrent(file) if !activeDownloads.contains(file) =>
      activeDownloads += (file -> watch(actorOf(StorrentDownload.props(file))))
  }
}

object StorrentDownload {
  def props(file: File): Props = Props(new StorrentDownload(file))

}

trait DownloadProps {
  def trackerProps(torrent: Torrent) = announce.Tracker.props(torrent)
  def peerManagerProps(torrent: Torrent) = PeerManager.props(torrent)
}

class StorrentDownload(file: File) extends Actor with ActorLogging with DownloadProps {
  import context._
  import peer.Discovered

  val torrent = Torrent.fromFile(file)
  val tracker = watch(actorOf(trackerProps(torrent), s"tracker-${torrent.name}"))
  val peers = actorOf(peerManagerProps(torrent), "peer-manager")

  def receive = LoggingReceive {
    case d @ Discovered(newPeers) =>
      peers forward d
  }
}
