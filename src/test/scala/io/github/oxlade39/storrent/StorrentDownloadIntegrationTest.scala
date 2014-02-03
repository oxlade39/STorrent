package io.github.oxlade39.storrent

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor._
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.FileOps
import concurrent.duration._
import akka.io.{IO, Tcp}
import java.net.InetSocketAddress
import scala.concurrent.{ExecutionContext, Future}
import io.github.oxlade39.storrent.piece.PieceManager
import io.github.oxlade39.storrent.peer.PeerManager
import io.github.oxlade39.storrent.piece.PieceManager.PeerPieceMappings
import akka.util.Timeout
import io.github.oxlade39.storrent.peer.PeerManager.ConnectedPeers
import java.util.Date
import java.text.SimpleDateFormat

class StorrentDownloadIntegrationTest extends TestKit(ActorSystem("StorrentDownloadIntegrationTest"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps {

  import StorrentDownloadIntegrationTest._

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "StorrentDownloadIntegrationTest" must {
    "start tracking" ignore {

      val fakePeer = system.actorOf(fakeTcpClient(self))

      val download = system.actorOf(Props(new StorrentDownload("examples" / "ubuntu.torrent")), "download")

      Thread.sleep(2.minutes.toMillis)
    }
  }

}

object StorrentDownloadIntegrationTest {

  val fakeClientAddress = new InetSocketAddress(0)

  def fakeTcpClient(recipient: ActorRef)(implicit sys: ActorSystem) = Props(new FakeClient(recipient))

  class FakeClient(recipient: ActorRef)(implicit sys: ActorSystem) extends Actor {
    IO(Tcp) ! Tcp.Bind(self, fakeClientAddress)

    def receive: Receive = {
      case msg => recipient forward msg
    }
  }

}

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
      log.info("downloaded {} pieces @ {}", response.localPieces.has.size, dateFormat(when))
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
      sys.shutdown()
    }
  })
}