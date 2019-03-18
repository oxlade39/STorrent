package io.github.oxlade39.storrent.example

import akka.actor.{ActorLogging, Actor, Props, ActorSystem}
import java.text.SimpleDateFormat
import java.util.Date
import akka.util.Timeout
import io.github.oxlade39.storrent.StorrentDownload
import io.github.oxlade39.storrent.piece.PieceManager
import io.github.oxlade39.storrent.piece.PieceManager.PeerPieceMappings
import io.github.oxlade39.storrent.peer.PeerManager
import io.github.oxlade39.storrent.peer.PeerManager.ConnectedPeers
import io.github.oxlade39.storrent.util.FileOps

object Example extends App with FileOps {
  import concurrent.duration._

  val sys = ActorSystem("Example")
  val dateFormat = new SimpleDateFormat("HH:mm:ss:SSS").format(_: Date)
  import sys._
  import akka.pattern.ask
  implicit val timeout = Timeout(1.minute)

  val download = sys.actorOf(StorrentDownload.props("examples" / "ubuntu-13.10-desktop-amd64.iso.torrent"), "ubuntu")

  sys.actorOf(Props(new Actor with ActorLogging {

    context.system.scheduler.schedule(30.seconds, 30.seconds, self, "poll")

    def findPieceManager(when: Date) = for {
      pieceManager <- context.actorSelection(download.path / "piece-manager").resolveOne(30.seconds)
      response <- (pieceManager ? PieceManager.GetPeerPieceMappings).mapTo[PeerPieceMappings]
    } yield {
      log.info("downloaded {} pieces ({}%) @ {}",
        response.localPieces.has.size,
        (response.localPieces.has.size.toDouble / response.localPieces.size) * 100,
        dateFormat(when))
    }

    def findPeerManager(when: Date) = for {
      peerManager <- context.actorSelection(download.path / "peer-manager").resolveOne(30.seconds)
      response <- (peerManager ? PeerManager.GetConnectedPeers).mapTo[ConnectedPeers]
    } yield {
      log.info("connected to {} peers @ {}", response.connected.size, dateFormat(when))
    }

    def receive = {
      case "poll" => findPieceManager(new Date()); findPeerManager(new Date())
    }
  }))

  sys.scheduler.scheduleOnce(60 minutes, new Runnable {
    def run() {
      sys.stop(download)
      sys.terminate()
    }
  })
}
