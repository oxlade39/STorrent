package io.github.oxlade39.storrent

import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import akka.testkit.{ImplicitSender, TestKit}
import akka.actor.{Props, ActorSystem}
import org.scalatest.matchers.MustMatchers
import io.github.oxlade39.storrent.test.util.FileOps
import io.github.oxlade39.storrent.peer.Discovered
import concurrent.duration._

class StorrentDownloadIntegrationTest extends TestKit(ActorSystem("StorrentDownloadIntegrationTest"))
with WordSpecLike with BeforeAndAfterAll with ImplicitSender with MustMatchers with FileOps {

  override def afterAll(): Unit = {
    system.shutdown()
  }

  "StorrentDownloadIntegrationTest" must {
    "start tracking" ignore {
      system.eventStream.subscribe(self, classOf[peer.Discovered])
      val download = system.actorOf(Props(new StorrentDownload("examples" / "ubuntu.torrent")), "download")
      val discovered: Discovered = expectMsgType[Discovered](20.seconds)
      println(discovered)
    }
  }

}
